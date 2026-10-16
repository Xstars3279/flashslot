package com.flashslot.booking.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.auth.entity.AppUser;
import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.auth.service.AccountValidator;
import com.flashslot.booking.dto.CreateBookingRequest;
import com.flashslot.booking.entity.BookingOrder;
import com.flashslot.booking.mapper.BookingMapper;
import com.flashslot.booking.service.BookingService;
import com.flashslot.booking.vo.*;
import com.flashslot.common.context.BaseContext;
import com.flashslot.common.service.IdempotentOperationService;
import com.flashslot.common.web.*;
import com.flashslot.messaging.entity.OutboxEvent;
import com.flashslot.messaging.mapper.OutboxEventMapper;
import com.flashslot.messaging.service.ScheduleEventService;
import com.flashslot.resource.entity.*;
import com.flashslot.resource.mapper.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.security.*;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;

/** 创建预约的唯一写路径；事务中只访问数据库，不调用 MQ 或 Redis。 */
@Service
public class BookingServiceImpl implements BookingService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    @Autowired private AccountPermissionMapper accounts;
    @Autowired private ResourceMapper resources;
    @Autowired private ResourceDayMapper days;
    @Autowired private BookingMapper bookings;
    @Autowired private IdempotentOperationService idempotency;
    @Autowired private ScheduleEventService scheduleEvents;
    @Autowired private OutboxEventMapper events;
    @Autowired private ObjectMapper json;
    @Autowired private Clock clock;

    /** 创建预约：所有订单、占用、事件和幂等结果在同一事务提交。 */
    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = CommittedBusinessException.class)
    public BookingView create(String key, CreateBookingRequest request) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "幂等键须为 1 至 64 位字母、数字或 ._:-");
        }
        long userId = BaseContext.getCurrentId();
        String canonical =
                request.getResourceId()
                        + "\n"
                        + request.getBookDate()
                        + "\n"
                        + request.getStartSlot()
                        + "\n"
                        + request.getEndSlot()
                        + "\n"
                        + request.getPurpose();
        // 1. 获取幂等记录；重复请求返回首次处理结果。
        var record = idempotency.prepare(userId, "CREATE_BOOKING", key, canonical);
        if (record.getResultHttpStatus() != null) {
            AccountValidator.requireEnabled(accounts.lockBookingAccount(userId));
            return idempotency.replay(record, BookingView.class);
        }

        // 2. 按设备、日历、候补、用户的顺序取得业务锁。
        Resource resource = resources.lockShared(request.getResourceId());
        ResourceDay day =
                resource == null
                        ? null
                        : days.lockDay(request.getResourceId(), request.getBookDate());
        var waitlist =
                day == null
                        ? List.<Long>of()
                        : bookings.lockOwnWaitlist(
                                request.getResourceId(),
                                request.getBookDate(),
                                userId,
                                request.getStartSlot(),
                                request.getEndSlot());
        AppUser account = accounts.lockBookingAccount(userId);
        Instant decisionTime = clock.instant();
        try {
            validate(request, resource, day, account, decisionTime);
        } catch (BusinessException ex) {
            // 校验拒绝时尚未写入订单，保存首次拒绝结果后交给全局异常处理器返回。
            idempotency.saveRejection(record, ex);
            throw new CommittedBusinessException(ex);
        }

        // 3. 校验通过，创建订单并写入设备和用户占用。
        BookingOrder order = newOrder(userId, request);
        requireOne(bookings.insert(order));
        var slots = IntStream.range(request.getStartSlot(), request.getEndSlot()).boxed().toList();
        // 防御绕过锁协议的其他写入；唯一键异常必须离开事务，回滚已插订单与所有格。
        try {
            if (bookings.occupyResource(order, slots) != slots.size()
                    || bookings.occupyUser(order, slots) != slots.size()) {
                throw new IllegalStateException("预约占用写入数量不完整");
            }
        } catch (DuplicateKeyException ex) {
            throw failure(HttpStatus.CONFLICT, "SLOT_CONFLICT", "所选设备或用户时段已被占用，请刷新后重试");
        }
        // 4. 更新候补和排期，保存事件及首次响应。
        for (long id : waitlist) requireOne(bookings.cancelWaitlist(id));
        requireOne(days.incrementRevision(request.getResourceId(), request.getBookDate()));
        scheduleEvents.record(
                request.getResourceId(),
                request.getBookDate(),
                day.getRevision() + 1,
                decisionTime);
        BookingView view = toView(order);
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("BOOKING_CREATED");
        event.setAggregateId(order.getOrderNo());
        event.setPayload(
                json.createObjectNode()
                        .put("schemaVersion", 1)
                        .put("userId", userId)
                        .set("booking", json.valueToTree(view)));
        event.setNextRunAt(decisionTime);
        requireOne(events.insert(event));
        idempotency.saveSuccess(record, 201, view);
        return view;
    }

    /** 所有时间条件使用取得业务锁后的同一时间，处罚到期不再按分数永久拒绝。 */
    private void validate(
            CreateBookingRequest request,
            Resource resource,
            ResourceDay day,
            AppUser account,
            Instant now) {
        AccountValidator.requireEnabled(account);
        if ("DEMO".equals(account.getRole()))
            throw failure(HttpStatus.FORBIDDEN, "FORBIDDEN", "演示账号不能预约正式设备");
        LocalDate today = now.atZone(BUSINESS_ZONE).toLocalDate();
        if (request.getBookDate().isBefore(today)
                || request.getBookDate().isAfter(today.plusDays(6))) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_DATE", "可预约日期为今天起七个业务日期");
        }
        if (resource == null) throw failure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "设备不存在");
        if (day == null)
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "CALENDAR_NOT_READY", "预约日历尚未生成");
        if (!"ACTIVE".equals(resource.getStatus())
                || !Boolean.TRUE.equals(day.getEnabled())
                || request.getStartSlot() < day.getOpenStartSlot()
                || request.getEndSlot() > day.getOpenEndSlot()) {
            throw failure(HttpStatus.CONFLICT, "RESOURCE_CLOSED", "设备未开放所选区间");
        }
        Instant start = slotTime(request.getBookDate(), request.getStartSlot());
        if (!now.isBefore(start.minusSeconds(1800)))
            throw failure(HttpStatus.CONFLICT, "BOOKING_CUTOFF", "已到预约截止时间");
        if (account.getRestrictionUntil() != null && now.isBefore(account.getRestrictionUntil())) {
            throw failure(HttpStatus.FORBIDDEN, "BOOKING_RESTRICTED", "账号当前处于预约限制期");
        }
        if (bookings.resourceConflicts(
                        request.getResourceId(),
                        request.getBookDate(),
                        request.getStartSlot(),
                        request.getEndSlot())
                > 0) {
            throw failure(HttpStatus.CONFLICT, "RESOURCE_SLOT_CONFLICT", "设备时段已被占用或正在候补交接");
        }
        if (bookings.userConflicts(
                        account.getId(),
                        request.getBookDate(),
                        request.getStartSlot(),
                        request.getEndSlot())
                > 0) {
            throw failure(HttpStatus.CONFLICT, "USER_SLOT_CONFLICT", "你在此时段已有其他预约");
        }
    }

    /** 根据连续区间生成订单及签到窗口，绝对时间统一保存为 UTC。 */
    private BookingOrder newOrder(long userId, CreateBookingRequest request) {
        BookingOrder order = new BookingOrder();
        order.setOrderNo("BK_" + UUID.randomUUID());
        order.setUserId(userId);
        order.setResourceId(request.getResourceId());
        order.setBookDate(request.getBookDate());
        order.setStartSlot(request.getStartSlot());
        order.setEndSlot(request.getEndSlot());
        order.setChargedSlots(request.getEndSlot() - request.getStartSlot());
        order.setPurpose(request.getPurpose());
        order.setStatus("CONFIRMED");
        order.setSource("DIRECT");
        order.setVersion(0L);
        order.setStartAt(slotTime(request.getBookDate(), request.getStartSlot()));
        order.setEndAt(slotTime(request.getBookDate(), request.getEndSlot()));
        order.setCheckInOpenAt(order.getStartAt().minusSeconds(600));
        order.setCheckInDeadline(order.getStartAt().plusSeconds(900));
        return order;
    }

    /** 显式挑选公开字段，避免实体内部身份及状态字段进入响应。 */
    private BookingView toView(BookingOrder order) {
        BookingView view = new BookingView();
        view.setOrderNo(order.getOrderNo());
        view.setResourceId(order.getResourceId());
        view.setBookDate(order.getBookDate());
        view.setStartSlot(order.getStartSlot());
        view.setEndSlot(order.getEndSlot());
        view.setPurpose(order.getPurpose());
        view.setStatus(order.getStatus());
        view.setSource(order.getSource());
        view.setChargedSlots(order.getChargedSlots());
        view.setStartAt(order.getStartAt());
        view.setEndAt(order.getEndAt());
        view.setCheckInOpenAt(order.getCheckInOpenAt());
        view.setCheckInDeadline(order.getCheckInDeadline());
        return view;
    }

    private Instant slotTime(LocalDate date, int slot) {
        return date.atStartOfDay(BUSINESS_ZONE).toInstant().plusSeconds(slot * 1800L);
    }

    private void requireOne(int rows) {
        if (rows != 1) throw new IllegalStateException("预约事务写入行数异常");
    }

    private BusinessException failure(HttpStatus status, String code, String message) {
        return new BusinessException(status, code, message);
    }
}
