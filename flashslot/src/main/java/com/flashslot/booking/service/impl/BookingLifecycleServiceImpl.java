package com.flashslot.booking.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.auth.entity.AppUser;
import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.booking.entity.BookingOrder;
import com.flashslot.booking.mapper.*;
import com.flashslot.booking.service.BookingLifecycleService;
import com.flashslot.credit.entity.CreditLedger;
import com.flashslot.credit.mapper.CreditLedgerMapper;
import com.flashslot.messaging.service.*;
import com.flashslot.resource.mapper.ResourceDayMapper;
import com.flashslot.resource.mapper.ResourceMapper;
import com.flashslot.waitlist.entity.ReleaseJob;
import com.flashslot.waitlist.mapper.ReleaseJobMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.UUID;

/** 共享订单状态转换；调用方必须已按资源、日历、订单、用户顺序持锁。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class BookingLifecycleServiceImpl implements BookingLifecycleService {
    @Autowired private BookingLifecycleMapper bookingMapper;
    @Autowired private BookingReadMapper reads;
    @Autowired private ResourceDayMapper dayMapper;
    @Autowired private ReleaseJobMapper jobMapper;
    @Autowired private CreditLedgerMapper creditMapper;
    @Autowired private BusinessEventService events;
    @Autowired private ScheduleEventService scheduleEvents;
    @Autowired private ObjectMapper json;

    @Autowired private AccountPermissionMapper accounts;
    @Autowired private ResourceMapper resources;
    @Autowired private Clock clock;

    /** 一次只结算一张订单；与后台扫描分离，失败不影响其他订单。 */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean expireOrder(long orderId) {
        var located = bookingMapper.find(orderId);
        if (located == null) return false;
        resources.lockShared(located.getResourceId());
        var day = dayMapper.lockDay(located.getResourceId(), located.getBookDate());
        var order = bookingMapper.lock(orderId);
        var owner = accounts.lockBookingAccount(order.getUserId());
        // 封禁、角色变更或设备停用不应阻止既有订单的履约结算。
        return normalizeLocked(order, owner, day.getRevision(), clock.instant());
    }

    @Override
    public boolean normalizeLocked(BookingOrder order, AppUser owner, long revision, Instant now) {
        if (("CONFIRMED".equals(order.getStatus()) && !now.isBefore(order.getCheckInDeadline()))
                || ("CHECKED_IN".equals(order.getStatus()) && !now.isBefore(order.getEndAt()))) {
            terminateLocked(order, owner, revision, now, false, "");
            return true;
        }
        return false;
    }

    @Override
    public void terminateLocked(
            BookingOrder order,
            AppUser owner,
            long revision,
            Instant decisionTime,
            boolean administrator,
            String reason) {
        boolean noShow =
                "CONFIRMED".equals(order.getStatus())
                        && !decisionTime.isBefore(order.getCheckInDeadline());
        boolean completed =
                "CHECKED_IN".equals(order.getStatus()) && !decisionTime.isBefore(order.getEndAt());
        int first = order.getStartSlot();
        Instant midnight = order.getBookDate().atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        // 最多 8 格，直接比较 Instant，保留精确到纳秒的截止语义。
        while (first < order.getEndSlot()
                && midnight.plusSeconds(first * 1800L).isBefore(decisionTime)) first++;
        int released = completed ? 0 : order.getEndSlot() - first;
        ReleaseJob job = null;
        if (released > 0) {
            job = new ReleaseJob();
            job.setJobNo("RJ_" + UUID.randomUUID());
            job.setSourceBookingId(order.getId());
            job.setResourceId(order.getResourceId());
            job.setBookDate(order.getBookDate());
            job.setStartSlot(order.getStartSlot());
            job.setEndSlot(order.getEndSlot());
            job.setEffectiveStartSlot(first);
            job.setStatus("PENDING");
            job.setAttempts(0);
            job.setNextRunAt(decisionTime);
            requireRows(jobMapper.insert(job), 1);
            requireRows(bookingMapper.handoff(order, first, job.getId()), released);
            requireRows(bookingMapper.releaseUser(order, first), released);
        }
        int charged = order.getChargedSlots() - released;
        if (charged < 0) throw new IllegalStateException("订单计费格数与占用不一致");
        order.setChargedSlots(charged);
        order.setStatus(completed ? "COMPLETED" : noShow ? "NO_SHOW" : "CANCELED");
        order.setTerminatedAt(completed ? null : decisionTime);
        order.setTerminationReason(
                completed
                        ? null
                        : noShow
                                ? "NO_SHOW"
                                : administrator
                                        ? "ADMIN_CANCEL"
                                        : order.getCheckedInAt() != null
                                                ? "EARLY_FINISH"
                                                : "USER_CANCEL");
        order.setReasonText(!completed && !noShow && !reason.isEmpty() ? reason : null);
        requireRows(bookingMapper.transition(order), 1);
        if (noShow) penalize(owner, order, decisionTime);
        requireRows(dayMapper.incrementRevision(order.getResourceId(), order.getBookDate()), 1);
        scheduleEvents.record(
                order.getResourceId(), order.getBookDate(), revision + 1, decisionTime);
        var view = reads.findVisible(order.getOrderNo(), order.getUserId(), true);
        events.record(
                completed ? "BOOKING_COMPLETED" : noShow ? "BOOKING_NO_SHOW" : "BOOKING_CANCELED",
                order.getOrderNo(),
                order.getUserId(),
                view,
                decisionTime);
        if (job != null)
            events.record(
                    "RELEASE_REQUESTED",
                    job.getJobNo(),
                    order.getUserId(),
                    json.createObjectNode()
                            .put("jobNo", job.getJobNo())
                            .put("orderNo", order.getOrderNo()),
                    decisionTime);
    }

    /** 爽约事件由订单终态和账本唯一键双重防重；分数低于 60 才延长固定 7 天处罚。 */
    private void penalize(AppUser owner, BookingOrder order, Instant now) {
        int before = owner.getCreditScore(), after = Math.max(0, before - 10);
        Instant restriction = owner.getRestrictionUntil();
        if (after < 60
                && (restriction == null || restriction.isBefore(now.plus(Duration.ofDays(7)))))
            restriction = now.plus(Duration.ofDays(7));
        CreditLedger ledger = new CreditLedger();
        ledger.setUserId(owner.getId());
        ledger.setEventType("NO_SHOW");
        ledger.setBusinessId(order.getId().toString());
        ledger.setDelta(after - before);
        ledger.setScoreBefore(before);
        ledger.setScoreAfter(after);
        ledger.setReason("预约未在签到截止前签到");
        requireRows(creditMapper.insert(ledger), 1);
        requireRows(bookingMapper.penalize(owner.getId(), after, restriction), 1);
        events.record(
                "CREDIT_CHANGED",
                order.getOrderNo(),
                owner.getId(),
                json.createObjectNode().put("scoreBefore", before).put("scoreAfter", after),
                now);
    }

    private void requireRows(int actual, int expected) {
        if (actual != expected) throw new IllegalStateException("订单与占用归属或数量不一致");
    }
}
