package com.flashslot.resource.service.impl;

import com.flashslot.auth.entity.AppUser;
import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.auth.service.CurrentAdmin;
import com.flashslot.common.context.BaseContext;
import com.flashslot.common.web.BusinessException;
import com.flashslot.messaging.service.ScheduleEventService;
import com.flashslot.operations.service.AdminAuditService;
import com.flashslot.resource.dto.UpdateResourceDayRequest;
import com.flashslot.resource.entity.Resource;
import com.flashslot.resource.entity.ResourceDay;
import com.flashslot.resource.mapper.*;
import com.flashslot.resource.model.ScheduleOccupation;
import com.flashslot.resource.service.ResourceScheduleService;
import com.flashslot.resource.service.ScheduleCacheService;
import com.flashslot.resource.vo.*;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ResourceScheduleServiceImpl implements ResourceScheduleService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final ResourceMapper resources;
    private final ResourceDayMapper days;
    private final ScheduleReadMapper scheduleReads;
    private final AccountPermissionMapper accounts;
    private final CurrentAdmin admin;
    private final AdminAuditService audit;
    private final ScheduleEventService events;
    private final Clock clock;
    private final ScheduleCacheService cache;

    /**
     * 使用同一个只读快照读取设备、日历和占用，避免 revision 与格子来自不同提交。 返回的 canBook/canWaitlist
     * 只表示排期条件，最终写入还需检查用户资格、时间冲突等。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ScheduleView getSchedule(long resourceId, LocalDate date) {
        Resource resource = requiredResource(resources.selectById(resourceId));
        ResourceDay day = requiredDay(days.findDay(resourceId, date));
        Instant now = clock.instant();
        Map<Integer, ScheduleOccupation> occupations = new HashMap<>();
        var publicOccupations = cache.get(resourceId, date, day.getRevision());
        if (publicOccupations == null) {
            publicOccupations = scheduleReads.findOccupations(resourceId, date, null);
            cache.put(resourceId, date, day.getRevision(), publicOccupations);
        }
        for (ScheduleOccupation occupation : publicOccupations) {
            occupations.put(occupation.getSlotIndex(), occupation);
        }
        Long viewer = viewerId();
        if (viewer != null) {
            for (ScheduleOccupation occupation :
                    scheduleReads.findOwnOccupations(resourceId, date, viewer)) {
                occupations.put(occupation.getSlotIndex(), occupation);
            }
        }
        var slots = new ArrayList<ScheduleSlotView>(48);
        Instant midnight = date.atStartOfDay(BUSINESS_ZONE).toInstant();
        for (int index = 0; index < 48; index++) {
            slots.add(toSlot(index, midnight, now, resource, day, occupations.get(index)));
        }
        ScheduleView view = new ScheduleView();
        view.setResourceId(resourceId);
        view.setBookDate(date);
        view.setRevision(day.getRevision());
        view.setResourceStatus(resource.getStatus());
        view.setEnabled(day.getEnabled());
        view.setOpenStartSlot(day.getOpenStartSlot());
        view.setOpenEndSlot(day.getOpenEndSlot());
        view.setServerTime(now);
        view.setSlots(slots);
        return view;
    }

    /** 单日修改事务：资源共享锁 → 日历排他锁 → 当前管理员锁 → 日历/事件/审计。 revision 防止旧页面覆盖新配置；行锁负责与预约、候补等未来写入口串行协调。 */
    @Override
    @Transactional
    public ResourceDayView updateDay(
            long resourceId, LocalDate date, UpdateResourceDayRequest request) {
        validateRange(request);
        if (date.isBefore(LocalDate.now(clock.withZone(BUSINESS_ZONE)))) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_DATE", "不能修改过去的日历");
        }
        Resource resource = requiredResource(resources.lockShared(resourceId));
        ResourceDay day = requiredDay(days.lockDay(resourceId, date));
        long actor = admin.lockAndRequireAdmin();
        Instant decisionTime = clock.instant();
        // 锁等待可能跨过上海午夜，必须按锁后的时间再次核对日期。
        if (date.isBefore(decisionTime.atZone(BUSINESS_ZONE).toLocalDate())) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_DATE", "不能修改过去的日历");
        }
        if (!day.getRevision().equals(request.getRevision())) {
            throw failure(HttpStatus.CONFLICT, "VERSION_CONFLICT", "日历已修改，请刷新后重试");
        }
        if ("RETIRED".equals(resource.getStatus()) && request.getEnabled()) {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATE", "退役设备不能重新开放日历");
        }
        boolean restrictive =
                !request.getEnabled()
                        || request.getOpenStartSlot() > day.getOpenStartSlot()
                        || request.getOpenEndSlot() < day.getOpenEndSlot();
        if (restrictive
                && scheduleReads.hasActiveUse(
                        resourceId, date, decisionTime, firstActiveSlot(date, decisionTime))) {
            throw failure(HttpStatus.CONFLICT, "CALENDAR_IN_USE", "当日仍有有效预约、候补或交接占用，不能关闭或缩短开放范围");
        }
        if (days.updateDay(resourceId, date, request) != 1) {
            throw failure(HttpStatus.CONFLICT, "VERSION_CONFLICT", "日历版本冲突，请刷新后重试");
        }
        long newRevision = day.getRevision() + 1;
        events.record(resourceId, date, newRevision, decisionTime);
        audit.recordTarget(
                actor, "RESOURCE_DAY_UPDATE", resourceId + ":" + date, request.getReason().strip());
        ResourceDayView view = new ResourceDayView();
        view.setResourceId(resourceId);
        view.setBookDate(date);
        view.setEnabled(request.getEnabled());
        view.setOpenStartSlot(request.getOpenStartSlot());
        view.setOpenEndSlot(request.getOpenEndSlot());
        view.setRevision(newRevision);
        return view;
    }

    /** 转换单格状态；开始时刻已到的格子显示 PAST，关闭范围显示 CLOSED。 PAUSED 的空闲格仍显示 FREE，但不允许新申请；提前 30 分钟截止，等于截止时刻也禁止。 */
    private ScheduleSlotView toSlot(
            int index,
            Instant midnight,
            Instant now,
            Resource resource,
            ResourceDay day,
            ScheduleOccupation occupation) {
        Instant start = midnight.plusSeconds(index * 1800L);
        String state;
        if (!start.isAfter(now)) state = "PAST";
        else if (!day.getEnabled()
                || index < day.getOpenStartSlot()
                || index >= day.getOpenEndSlot()
                || "RETIRED".equals(resource.getStatus())) state = "CLOSED";
        else state = occupation == null ? "FREE" : occupation.getState();
        boolean applicationsOpen =
                "ACTIVE".equals(resource.getStatus()) && now.isBefore(start.minusSeconds(1800));
        ScheduleSlotView slot = new ScheduleSlotView();
        slot.setSlotIndex(index);
        slot.setStartAt(start);
        slot.setEndAt(start.plusSeconds(1800));
        slot.setState(state);
        slot.setMine(occupation != null && occupation.getOwnOrderNo() != null);
        slot.setOrderNo(occupation == null ? null : occupation.getOwnOrderNo());
        slot.setCanBook(applicationsOpen && "FREE".equals(state));
        slot.setCanWaitlist(
                applicationsOpen && ("BOOKED".equals(state) || "HANDOFF".equals(state)));
        return slot;
    }

    /** 仅启用中的可信登录账号可以读取自己的订单号，匿名或失效身份返回 null。 */
    private Long viewerId() {
        Long userId = BaseContext.getCurrentIdOrNull();
        if (userId == null) return null;
        AppUser account = accounts.findCurrentAccountById(userId);
        return account != null && Boolean.TRUE.equals(account.getEnabled())
                ? account.getId()
                : null;
    }

    /** 计算仍在进行/未来的首个格号；今天包含当前半小时，未来日期从第 0 格起。 */
    private int firstActiveSlot(LocalDate date, Instant now) {
        var local = now.atZone(BUSINESS_ZONE);
        return date.equals(local.toLocalDate())
                ? (local.getHour() * 60 + local.getMinute()) / 30
                : 0;
    }

    /** 对组合开放区间进行校验，单字段范围和必填由 DTO 验证完成。 */
    private void validateRange(UpdateResourceDayRequest request) {
        if (request.getOpenStartSlot() >= request.getOpenEndSlot()) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_SLOT_RANGE", "起始时间格必须小于结束时间格");
        }
    }

    /** 不存在设备返回 404，与尚未生成日历的可恢复错误区分。 */
    private Resource requiredResource(Resource resource) {
        if (resource == null) throw failure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "设备不存在");
        return resource;
    }

    /** 日历必须预先存在，不在查询中隐式建行，保证后续行锁目标明确。 */
    private ResourceDay requiredDay(ResourceDay day) {
        if (day == null)
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "CALENDAR_NOT_READY", "该日期日历尚未生成，请稍后重试");
        return day;
    }

    /** 统一创建携带 HTTP 状态与业务码的异常，由全局处理器返回 Result。 */
    private BusinessException failure(HttpStatus status, String code, String message) {
        return new BusinessException(status, code, message);
    }
}
