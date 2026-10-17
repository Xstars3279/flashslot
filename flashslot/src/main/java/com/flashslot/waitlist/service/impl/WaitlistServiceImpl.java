package com.flashslot.waitlist.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.auth.entity.AppUser;
import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.auth.service.AccountValidator;
import com.flashslot.booking.mapper.BookingMapper;
import com.flashslot.common.context.BaseContext;
import com.flashslot.common.service.IdempotentOperationService;
import com.flashslot.common.web.*;
import com.flashslot.messaging.service.BusinessEventService;
import com.flashslot.resource.entity.*;
import com.flashslot.resource.mapper.*;
import com.flashslot.waitlist.dto.CreateWaitlistRequest;
import com.flashslot.waitlist.entity.WaitlistRecord;
import com.flashslot.waitlist.mapper.WaitlistMapper;
import com.flashslot.waitlist.service.WaitlistService;
import com.flashslot.waitlist.vo.WaitlistPage;
import com.flashslot.waitlist.vo.WaitlistView;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.UUID;

@Service
public class WaitlistServiceImpl implements WaitlistService {
    @Autowired private IdempotentOperationService operations;
    @Autowired private ResourceMapper resourceMapper;
    @Autowired private ResourceDayMapper dayMapper;
    @Autowired private AccountPermissionMapper accountMapper;
    @Autowired private BookingMapper bookingMapper;
    @Autowired private WaitlistMapper waitlistMapper;
    @Autowired private BusinessEventService events;
    @Autowired private ObjectMapper json;
    @Autowired private Clock clock;

    /** 与撮合采用相同锁序；已转正的候补只能通过预约取消接口处理。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WaitlistView cancel(String number, String key) {
        long userId = BaseContext.getCurrentId();
        var operation = operations.prepare(userId, "CANCEL_WAITLIST", key, number);
        if (operation.getResultHttpStatus() != null) {
            AccountValidator.requireUser(accountMapper.lockBookingAccount(userId));
            var located = waitlistMapper.locate(number);
            if (located == null || located.getUserId() != userId)
                throw failure(HttpStatus.NOT_FOUND, "WAITLIST_NOT_FOUND", "候补不存在");
            return operations.replay(operation, WaitlistView.class);
        }
        var located = waitlistMapper.locate(number);
        if (located == null || located.getUserId() != userId) {
            AccountValidator.requireUser(accountMapper.lockBookingAccount(userId));
            throw failure(HttpStatus.NOT_FOUND, "WAITLIST_NOT_FOUND", "候补不存在");
        }
        resourceMapper.lockShared(located.getResourceId());
        dayMapper.lockDay(located.getResourceId(), located.getBookDate());
        var candidate = waitlistMapper.lock(located.getId());
        AccountValidator.requireUser(accountMapper.lockBookingAccount(userId));
        Instant now = clock.instant();
        if ("WAITING".equals(candidate.getStatus())) {
            boolean expired = !now.isBefore(candidate.getExpireAt());
            String state = expired ? "EXPIRED" : "CANCELED";
            if (waitlistMapper.finish(candidate.getId(), state, expired ? "CUTOFF" : "USER_CANCEL")
                    != 1) throw new IllegalStateException("候补状态更新失败");
            events.record(
                    expired ? "WAITLIST_EXPIRED" : "WAITLIST_CANCELED",
                    number,
                    userId,
                    json.createObjectNode().put("waitlistNo", number).put("status", state),
                    now);
        } else if (!"CANCELED".equals(candidate.getStatus())
                && !"EXPIRED".equals(candidate.getStatus())) {
            throw failure(HttpStatus.CONFLICT, "WAITLIST_TERMINAL", "候补已处理；已转正请取消对应预约");
        }
        var view = waitlistMapper.findOwn(number, userId, now);
        operations.saveSuccess(operation, 200, view);
        return view;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = CommittedBusinessException.class)
    public WaitlistView create(String key, CreateWaitlistRequest request) {
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
        var record = operations.prepare(userId, "CREATE_WAITLIST", key, canonical);
        if (record.getResultHttpStatus() != null) {
            AccountValidator.requireUser(accountMapper.lockBookingAccount(userId));
            return operations.replay(record, WaitlistView.class);
        }
        try {
            WaitlistView view = createLocked(userId, request);
            operations.saveSuccess(record, 201, view);
            return view;
        } catch (CommittedBusinessException ex) {
            operations.saveRejection(record, ex);
            throw ex;
        }
    }

    /** 资源 → 日历 → 本人重复候补 → 用户，所有资格以锁后判定时间为准。 */
    private WaitlistView createLocked(long userId, CreateWaitlistRequest request) {
        Resource resource = resourceMapper.lockShared(request.getResourceId());
        ResourceDay day =
                resource == null
                        ? null
                        : dayMapper.lockDay(request.getResourceId(), request.getBookDate());
        var duplicates =
                day == null
                        ? java.util.List.<Long>of()
                        : bookingMapper.lockOwnWaitlist(
                                request.getResourceId(),
                                request.getBookDate(),
                                userId,
                                request.getStartSlot(),
                                request.getEndSlot());
        AppUser account = accountMapper.lockBookingAccount(userId);
        Instant decisionTime = clock.instant();
        try {
            AccountValidator.requireUser(account);
            LocalDate today = decisionTime.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
            if (request.getBookDate().isBefore(today)
                    || request.getBookDate().isAfter(today.plusDays(6)))
                throw failure(HttpStatus.BAD_REQUEST, "INVALID_DATE", "可申请日期为今天起七天");
            if (resource == null)
                throw failure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "设备不存在");
            if (day == null)
                throw failure(HttpStatus.SERVICE_UNAVAILABLE, "CALENDAR_NOT_READY", "预约日历尚未生成");
            if (!"ACTIVE".equals(resource.getStatus())
                    || !Boolean.TRUE.equals(day.getEnabled())
                    || request.getStartSlot() < day.getOpenStartSlot()
                    || request.getEndSlot() > day.getOpenEndSlot())
                throw failure(HttpStatus.CONFLICT, "RESOURCE_CLOSED", "设备未开放所选区间");
            Instant start =
                    request.getBookDate()
                            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                            .toInstant()
                            .plusSeconds(request.getStartSlot() * 1800L);
            if (!decisionTime.isBefore(start.minusSeconds(1800)))
                throw failure(HttpStatus.CONFLICT, "BOOKING_CUTOFF", "已到申请截止时间");
            if (account.getRestrictionUntil() != null
                    && decisionTime.isBefore(account.getRestrictionUntil()))
                throw failure(HttpStatus.FORBIDDEN, "BOOKING_RESTRICTED", "账号当前处于预约限制期");
            if (!duplicates.isEmpty())
                throw failure(HttpStatus.CONFLICT, "WAITLIST_DUPLICATE", "已有同设备同区间的待处理候补");
            if (waitlistMapper.waitingCount(userId, request.getBookDate(), decisionTime) >= 3)
                throw failure(HttpStatus.CONFLICT, "WAITLIST_LIMIT", "每日最多三个待处理候补");
            if (bookingMapper.userConflicts(
                            userId,
                            request.getBookDate(),
                            request.getStartSlot(),
                            request.getEndSlot())
                    > 0) throw failure(HttpStatus.CONFLICT, "USER_SLOT_CONFLICT", "你在此时段已有预约");
            if (bookingMapper.resourceConflicts(
                            request.getResourceId(),
                            request.getBookDate(),
                            request.getStartSlot(),
                            request.getEndSlot())
                    == 0) throw failure(HttpStatus.CONFLICT, "WAITLIST_NOT_NEEDED", "区间空闲，请直接预约");
        } catch (BusinessException ex) {
            throw new CommittedBusinessException(ex);
        }
        WaitlistRecord record = new WaitlistRecord();
        record.setWaitlistNo("WL_" + UUID.randomUUID());
        record.setUserId(userId);
        record.setResourceId(request.getResourceId());
        record.setBookDate(request.getBookDate());
        record.setStartSlot(request.getStartSlot());
        record.setEndSlot(request.getEndSlot());
        record.setPurpose(request.getPurpose());
        record.setStatus("WAITING");
        record.setExpireAt(
                request.getBookDate()
                        .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                        .toInstant()
                        .plusSeconds(request.getStartSlot() * 1800L - 600));
        try {
            if (waitlistMapper.insert(record) != 1) throw new IllegalStateException("候补写入失败");
        } catch (DuplicateKeyException ex) {
            throw failure(HttpStatus.CONFLICT, "WAITLIST_DUPLICATE", "已有同区间待处理候补");
        }
        var view = waitlistMapper.findOwn(record.getWaitlistNo(), userId, decisionTime);
        events.record("WAITLIST_CREATED", record.getWaitlistNo(), userId, view, decisionTime);
        return view;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WaitlistPage list(String cursor, int limit) {
        long userId = BaseContext.getCurrentId();
        AccountValidator.requireUser(accountMapper.findCurrentAccountById(userId));
        if (limit < 1
                || limit > 100
                || (cursor != null && !cursor.matches("[A-Za-z0-9._:-]{0,64}")))
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "分页参数不合法");
        WaitlistRecord anchor = null;
        if (cursor != null && !cursor.isEmpty()) {
            anchor = waitlistMapper.findOwnCursor(cursor, userId);
            if (anchor == null) throw failure(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "分页游标无效");
        }
        var found = waitlistMapper.findOwnPage(userId, anchor, limit + 1, clock.instant());
        boolean more = found.size() > limit;
        var items = found.stream().limit(limit).toList();
        return new WaitlistPage(
                items, more ? items.get(items.size() - 1).getWaitlistNo() : null, more);
    }

    private BusinessException failure(HttpStatus status, String code, String message) {
        return new BusinessException(status, code, message);
    }
}
