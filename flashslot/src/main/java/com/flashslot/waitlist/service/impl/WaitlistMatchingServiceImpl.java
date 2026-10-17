package com.flashslot.waitlist.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.auth.entity.AppUser;
import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.auth.service.AccountValidator;
import com.flashslot.booking.entity.BookingOrder;
import com.flashslot.booking.mapper.*;
import com.flashslot.messaging.service.*;
import com.flashslot.resource.entity.*;
import com.flashslot.resource.mapper.*;
import com.flashslot.waitlist.entity.*;
import com.flashslot.waitlist.mapper.ReleaseWorkMapper;
import com.flashslot.waitlist.service.WaitlistMatchingService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;
import java.util.stream.IntStream;

/** 每个候选独立事务；扫描预算不意味着可以跳过未处理队列直接释放库存。 */
@Service
public class WaitlistMatchingServiceImpl implements WaitlistMatchingService {
    /** 设备暂停期间的复查间隔，避免补偿扫描每两分钟重复派发。 */
    private static final Duration PAUSED_RECHECK = Duration.ofMinutes(5);
    /** 第 1–4 次失败的退避；第 5 次进入人工检查。 */
    private static final Duration[] FAILURE_BACKOFF = {
        Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(30)
    };
    @Autowired private ReleaseWorkMapper workMapper;
    @Autowired private ResourceMapper resourceMapper;
    @Autowired private ResourceDayMapper dayMapper;
    @Autowired private AccountPermissionMapper accountMapper;
    @Autowired private BookingMapper bookingMapper;
    @Autowired private BookingReadMapper bookingReads;
    @Autowired private ScheduleEventService scheduleEvents;
    @Autowired private BusinessEventService events;
    @Autowired private ObjectMapper json;
    @Autowired private Clock clock;

    /** 返回 true 表示任务终结或尚未到处理时间；false 表示下一短事务继续选队首。 */
    @Override
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public boolean matchNext(long id) {
        ReleaseJob located = workMapper.locate(id);
        if (located == null) return true;
        Resource resource = resourceMapper.lockShared(located.getResourceId());
        ResourceDay day = dayMapper.lockDay(located.getResourceId(), located.getBookDate());
        ReleaseJob job = workMapper.lock(id);
        if (job == null || !"PENDING".equals(job.getStatus())) return true;
        Instant beforeUserLock = clock.instant();
        if (job.getNextRunAt().isAfter(beforeUserLock)) return true;
        BookingOrder source = bookingMapper.selectById(job.getSourceBookingId());
        requireSource(job, source);
        requireOwnership(job);
        int proposed = firstFuture(job, beforeUserLock);
        String mode = mode(resource, day, job, proposed, beforeUserLock);
        // 暂停不创建新订单，也不拒绝已有候补；推迟复查时间，恢复后由补偿扫描继续，超过截止仍按原逻辑过期清理。
        if(resource!=null && "PAUSED".equals(resource.getStatus()) && "NORMAL".equals(mode)) {
            job.setNextRunAt(beforeUserLock.plus(PAUSED_RECHECK));
            requireRows(workMapper.save(job), 1);
            return true;
        }
        int maxStart =
                "CLOSED".equals(mode)
                        ? job.getEndSlot() - 1
                        : Math.min(proposed, job.getEndSlot() - 1);
        WaitlistRecord candidate =
                workMapper.lockCandidate(job, proposed, maxStart, mode, beforeUserLock);
        AppUser account =
                candidate == null ? null : accountMapper.lockBookingAccount(candidate.getUserId());
        // 所有本事务需要的锁都已取得，此后只使用这个判定时间。
        Instant decisionTime = clock.instant();
        int first = firstFuture(job, decisionTime);
        boolean changed = first > job.getEffectiveStartSlot();
        if (changed) {
            requireRows(workMapper.trimStarted(job, first), first - job.getEffectiveStartSlot());
            job.setEffectiveStartSlot(first);
        }
        if (first != proposed) {
            saveProgress(job, decisionTime);
            scheduleChanged(job, day, decisionTime);
            return false; // 等锁期间后缀变化，重新选取精确队列，不把候补自动改时间。
        }
        String finalMode = mode(resource, day, job, first, decisionTime);
        if (candidate == null) {
            // 日历锁内已经再次确认队列没有合适候选，才能释放仍精确归属本任务的占用。
            requireRows(workMapper.releaseRemaining(job), job.getEndSlot() - first);
            job.setEffectiveStartSlot(job.getEndSlot());
            job.setStatus("RELEASED");
            saveProgress(job, decisionTime);
            scheduleChanged(job, day, decisionTime);
            events.record(
                    "RELEASE_COMPLETED",
                    job.getJobNo(),
                    source.getUserId(),
                    json.createObjectNode().put("jobNo", job.getJobNo()).put("status", "RELEASED"),
                    decisionTime);
            return true;
        }
        String rejection = null;
        String candidateStatus = "REJECTED";
        if ("CLOSED".equals(finalMode)) rejection = "RESOURCE_CLOSED";
        else if (!decisionTime.isBefore(candidate.getExpireAt()) || "CUTOFF".equals(finalMode)) {
            rejection = "CUTOFF";
            candidateStatus = "EXPIRED";
        } else if (!AccountValidator.isEnabled(account)) rejection = "ACCOUNT_DISABLED";
        else if (!AccountValidator.isUser(account)) rejection = "ACCOUNT_INVALID";
        else if (account.getRestrictionUntil() != null
                && decisionTime.isBefore(account.getRestrictionUntil()))
            rejection = "BOOKING_RESTRICTED";
        else if (bookingMapper.userConflicts(
                        account.getId(), job.getBookDate(), first, job.getEndSlot())
                > 0) rejection = "USER_SLOT_CONFLICT";
        else if(bookingMapper.dailyChargedSlots(account.getId(),job.getBookDate())+job.getEndSlot()-first>8)
            rejection = "DAILY_QUOTA_EXCEEDED";
        if (rejection != null) {
            requireRows(
                    workMapper.transitionCandidate(
                            candidate.getId(), candidateStatus, rejection, null),
                    1);
            saveProgress(job, decisionTime);
            if (changed) scheduleChanged(job, day, decisionTime);
            events.record(
                    "EXPIRED".equals(candidateStatus) ? "WAITLIST_EXPIRED" : "WAITLIST_REJECTED",
                    candidate.getWaitlistNo(),
                    candidate.getUserId(),
                    json.createObjectNode()
                            .put("waitlistNo", candidate.getWaitlistNo())
                            .put("status", candidateStatus)
                            .put("reason", rejection),
                    decisionTime);
            return false;
        }
        if (candidate.getStartSlot() != first || !candidate.getEndSlot().equals(job.getEndSlot()))
            throw new OwnershipException();
        BookingOrder order = newOrder(job, candidate, first);
        requireRows(bookingMapper.insert(order), 1);
        var slots = IntStream.range(first, job.getEndSlot()).boxed().toList();
        requireRows(bookingMapper.occupyUser(order, slots), slots.size());
        requireRows(workMapper.assign(job, first, order.getId()), slots.size());
        requireRows(
                workMapper.transitionCandidate(candidate.getId(), "MATCHED", null, order.getId()),
                1);
        job.setStatus("MATCHED");
        saveProgress(job, decisionTime);
        scheduleChanged(job, day, decisionTime);
        var view = bookingReads.findVisible(order.getOrderNo(), candidate.getUserId(), false);
        events.record(
                "BOOKING_CREATED", order.getOrderNo(), candidate.getUserId(), view, decisionTime);
        events.record(
                "WAITLIST_MATCHED",
                candidate.getWaitlistNo(),
                candidate.getUserId(),
                json.createObjectNode()
                        .put("waitlistNo", candidate.getWaitlistNo())
                        .put("orderNo", order.getOrderNo()),
                decisionTime);
        events.record(
                "RELEASE_MATCHED",
                job.getJobNo(),
                candidate.getUserId(),
                json.createObjectNode()
                        .put("jobNo", job.getJobNo())
                        .put("orderNo", order.getOrderNo()),
                decisionTime);
        return true;
    }

    private String mode(
            Resource resource, ResourceDay day, ReleaseJob job, int first, Instant now) {
        if (resource == null
                || day == null
                || "RETIRED".equals(resource.getStatus())
                || !Boolean.TRUE.equals(day.getEnabled())
                || first < day.getOpenStartSlot()
                || job.getEndSlot() > day.getOpenEndSlot()) return "CLOSED";
        return first >= job.getEndSlot() || !now.isBefore(slotTime(job, first).minusSeconds(600))
                ? "CUTOFF"
                : "NORMAL";
    }

    private int firstFuture(ReleaseJob job, Instant now) {
        int first = job.getEffectiveStartSlot();
        while (first < job.getEndSlot() && slotTime(job, first).isBefore(now)) first++;
        return first;
    }

    private Instant slotTime(ReleaseJob job, int slot) {
        return job.getBookDate()
                .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                .toInstant()
                .plusSeconds(slot * 1800L);
    }

    private BookingOrder newOrder(ReleaseJob job, WaitlistRecord candidate, int first) {
        BookingOrder order = new BookingOrder();
        order.setOrderNo("BK_" + UUID.randomUUID());
        order.setUserId(candidate.getUserId());
        order.setResourceId(job.getResourceId());
        order.setBookDate(job.getBookDate());
        order.setStartSlot(first);
        order.setEndSlot(job.getEndSlot());
        order.setChargedSlots(job.getEndSlot() - first);
        order.setStatus("CONFIRMED");
        order.setSource("WAITLIST");
        order.setPurpose(candidate.getPurpose());
        order.setWaitlistId(candidate.getId());
        order.setReleaseJobId(job.getId());
        order.setVersion(0L);
        order.setStartAt(slotTime(job, first));
        order.setEndAt(slotTime(job, job.getEndSlot()));
        order.setCheckInOpenAt(order.getStartAt().minusSeconds(600));
        order.setCheckInDeadline(order.getStartAt().plusSeconds(900));
        return order;
    }

    /** 不只检查行数，每个格的资源、日期、序号和归属都必须与任务一致。 */
    private void requireOwnership(ReleaseJob job) {
        var slots = workMapper.ownedSlots(job.getId());
        if (slots.size() != job.getEndSlot() - job.getEffectiveStartSlot())
            throw new OwnershipException();
        for (int index = 0; index < slots.size(); index++) {
            var slot = slots.get(index);
            if (!job.getResourceId().equals(slot.getResourceId())
                    || !job.getBookDate().equals(slot.getBookDate())
                    || !"HANDOFF".equals(slot.getState())
                    || slot.getBookingId() != null
                    || slot.getSlotIndex() != job.getEffectiveStartSlot() + index)
                throw new OwnershipException();
        }
    }

    private void requireSource(ReleaseJob job, BookingOrder source) {
        if (source == null
                || !Set.of("CANCELED", "NO_SHOW").contains(source.getStatus())
                || !source.getResourceId().equals(job.getResourceId())
                || !source.getBookDate().equals(job.getBookDate())
                || !source.getStartSlot().equals(job.getStartSlot())
                || !source.getEndSlot().equals(job.getEndSlot())) throw new OwnershipException();
    }

    private void saveProgress(ReleaseJob job, Instant now) {
        job.setAttempts(0);
        job.setLastError(null);
        job.setNextRunAt(now);
        requireRows(workMapper.save(job), 1);
    }

    private void scheduleChanged(ReleaseJob job, ResourceDay day, Instant now) {
        requireRows(dayMapper.incrementRevision(job.getResourceId(), job.getBookDate()), 1);
        scheduleEvents.record(job.getResourceId(), job.getBookDate(), day.getRevision() + 1, now);
    }

    /** 失败事务先回滚，再用相同锁序记录重试，不能覆盖其他执行器的终态。 */
    @Override
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public void recordFailure(long id, RuntimeException error) {
        var located = workMapper.locate(id);
        if (located == null) return;
        resourceMapper.lockShared(located.getResourceId());
        dayMapper.lockDay(located.getResourceId(), located.getBookDate());
        var job = workMapper.lock(id);
        if (job == null || !"PENDING".equals(job.getStatus())) return;
        // 同一轮重复投递的多个失败只能累计一次，不能把一次故障放大为五次人工检查。
        Instant failureTime=clock.instant();
        if(job.getNextRunAt().isAfter(failureTime)) return;
        int attempts = job.getAttempts() + 1;
        job.setAttempts(attempts);
        if (error instanceof OwnershipException
                || error instanceof DataIntegrityViolationException
                || attempts >= 5) job.setStatus("MANUAL_REVIEW");
        job.setLastError(error.getClass().getSimpleName());
        job.setNextRunAt(failureTime.plus(FAILURE_BACKOFF[Math.min(attempts, FAILURE_BACKOFF.length) - 1]));
        requireRows(workMapper.save(job), 1);
    }

    private void requireRows(int actual, int expected) {
        if (actual != expected) throw new OwnershipException();
    }

    private static class OwnershipException extends IllegalStateException {
        private OwnershipException() {
            super("释放任务与业务占用不一致");
        }
    }
}
