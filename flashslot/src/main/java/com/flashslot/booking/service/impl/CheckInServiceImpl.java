package com.flashslot.booking.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.auth.entity.AppUser;
import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.auth.service.AccountValidator;
import com.flashslot.auth.service.CurrentAdmin;
import com.flashslot.booking.mapper.*;
import com.flashslot.booking.service.*;
import com.flashslot.booking.vo.BookingDetailView;
import com.flashslot.booking.vo.CheckInCodeView;
import com.flashslot.common.context.BaseContext;
import com.flashslot.common.web.*;
import com.flashslot.messaging.service.*;
import com.flashslot.resource.mapper.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;

@Service
public class CheckInServiceImpl implements CheckInService {
    @Autowired private ResourceMapper resources;
    @Autowired private ResourceDayMapper days;
    @Autowired private BookingLifecycleMapper orders;
    @Autowired private BookingReadMapper reads;
    @Autowired private AccountPermissionMapper accounts;
    @Autowired private CheckInCodeSigner signer;
    @Autowired private BookingLifecycleService lifecycle;
    @Autowired private BusinessEventService events;
    @Autowired private ScheduleEventService scheduleEvents;
    @Autowired private ObjectMapper json;
    @Autowired private Clock clock;

    @Autowired private CurrentAdmin admin;

    @Override
    @Transactional
    public CheckInCodeView issueCode(long resourceId) {
        var resource = resources.lockShared(resourceId);
        admin.lockAndRequireAdmin();
        if (resource == null) throw failure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "设备不存在");
        return signer.issue(resourceId, clock.instant());
    }

    /** 本人签到；业务拒绝返回结果，超时归一化仍提交。 */
    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = CommittedBusinessException.class)
    public BookingDetailView checkIn(String orderNo, String code) {
        long actor = BaseContext.getCurrentId();
        var located = orders.locate(orderNo);
        if (located == null || located.getUserId() != actor)
            throw failure(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "订单不存在");
        resources.lockShared(located.getResourceId());
        var day = days.lockDay(located.getResourceId(), located.getBookDate());
        var order = orders.lock(located.getId());
        AppUser owner = accounts.lockBookingAccount(actor);
        AccountValidator.requireUser(owner);
        Instant now = clock.instant();
        lifecycle.normalizeLocked(order, owner, day.getRevision(), now);
        if ("NO_SHOW".equals(order.getStatus()))
            throw new CommittedBusinessException(
                    failure(HttpStatus.UNPROCESSABLE_ENTITY, "CHECKIN_EXPIRED", "已超过签到截止时间"));
        if (order.getCheckedInAt() != null
                && ("CHECKED_IN".equals(order.getStatus())
                        || "COMPLETED".equals(order.getStatus()))) return result(orderNo, actor);
        if (!"CONFIRMED".equals(order.getStatus()))
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATE", "当前订单不可签到");
        if (now.isBefore(order.getCheckInOpenAt()))
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "CHECKIN_NOT_OPEN", "尚未到签到时间");
        if (!signer.valid(code, order.getResourceId(), now))
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "CHECKIN_CODE_INVALID", "签到码无效或已过期，请刷新");
        if (orders.checkIn(order.getId(), order.getVersion(), now) != 1)
            throw new IllegalStateException("签到状态更新失败");
        if (days.incrementRevision(order.getResourceId(), order.getBookDate()) != 1)
            throw new IllegalStateException("日历更新失败");
        scheduleEvents.record(
                order.getResourceId(), order.getBookDate(), day.getRevision() + 1, now);
        var view = reads.findVisible(orderNo, actor, false);
        events.record("BOOKING_CHECKED_IN", orderNo, actor, view, now);
        return view;
    }

    private BookingDetailView result(String orderNo, long actor) {
        return reads.findVisible(orderNo, actor, false);
    }

    private BusinessException failure(HttpStatus status, String code, String message) {
        return new BusinessException(status, code, message);
    }
}
