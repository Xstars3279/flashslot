package com.flashslot.operations.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.flashslot.auth.service.CurrentAdmin;
import com.flashslot.booking.mapper.BookingMapper;
import com.flashslot.common.context.BaseContext;
import com.flashslot.common.service.IdempotentOperationService;
import com.flashslot.common.web.BusinessException;
import com.flashslot.messaging.service.ScheduleEventService;
import com.flashslot.operations.dto.RecoveryRequest;
import com.flashslot.operations.service.*;
import com.flashslot.operations.vo.*;
import com.flashslot.resource.mapper.*;
import com.flashslot.waitlist.entity.ReleaseJob;
import com.flashslot.waitlist.mapper.*;
import com.flashslot.waitlist.service.ReleaseDispatchService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Set;

@Service
public class ReleaseRecoveryServiceImpl implements ReleaseRecoveryService {
    @Autowired private ReleaseJobMapper jobs;
    @Autowired private ReleaseWorkMapper work;
    @Autowired private ResourceMapper resources;
    @Autowired private ResourceDayMapper days;
    @Autowired private BookingMapper bookings;
    @Autowired private CurrentAdmin admin;
    @Autowired private IdempotentOperationService operations;
    @Autowired private AdminAuditService audit;
    @Autowired private ScheduleEventService events;
    @Autowired private Clock clock;
    @Autowired private ReleaseDispatchService dispatch;

    @Override
    @Transactional(readOnly = true)
    public RecoveryPage<ReleaseRecoveryView> list(Long cursor, int limit) {
        admin.requireAdmin();
        if (limit < 1 || limit > 100 || (cursor != null && cursor < 1))
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "分页参数不合法");
        var query = new QueryWrapper<ReleaseJob>().eq("status", "MANUAL_REVIEW");
        if (cursor != null) query.gt("id", cursor);
        var found = jobs.selectList(query.orderByAsc("id").last("LIMIT " + (limit + 1)));
        var items =
                found.stream()
                        .limit(limit)
                        .map(
                                j ->
                                        new ReleaseRecoveryView(
                                                j.getId(),
                                                j.getJobNo(),
                                                j.getResourceId(),
                                                j.getBookDate(),
                                                j.getEffectiveStartSlot(),
                                                j.getEndSlot(),
                                                j.getStatus(),
                                                j.getAttempts(),
                                                j.getLastError()))
                        .toList();
        boolean more = found.size() > limit;
        return new RecoveryPage<>(
                items, more ? Long.toString(items.get(items.size() - 1).id()) : null, more);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RecoveryActionView retry(String jobNo, String key, RecoveryRequest request) {
        long actor = BaseContext.getCurrentId();
        String reason = request.getReason().strip();
        var operation = operations.prepare(actor, "RELEASE_RETRY", key, jobNo + "\n" + reason);
        var job = lock(jobNo);
        if (operation.getResultHttpStatus() != null)
            return operations.replay(operation, RecoveryActionView.class);
        verify(job);
        var now = clock.instant();
        if (jobs.update(
                        null,
                        new UpdateWrapper<ReleaseJob>()
                                .eq("id", job.getId())
                                .eq("status", "MANUAL_REVIEW")
                                .set("status", "PENDING")
                                .set("attempts", 0)
                                .set("last_error", null)
                                .set("next_run_at", now))
                != 1) throw new IllegalStateException("任务重试更新失败");
        // 与状态重置同事务写入派发指令，提交后立即重新撮合；同一分钟已有指令时由补偿扫描补派。
        dispatch.dispatch(job.getId(), now);
        audit.recordTarget(actor, "RELEASE_RETRY", jobNo, reason);
        var result = new RecoveryActionView(jobNo, "PENDING");
        operations.saveSuccess(operation, 200, result);
        return result;
    }

    /** 人工放回库存仍须核对完整归属，不能绕过损坏数据的保护。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public RecoveryActionView release(String jobNo, String key, RecoveryRequest request) {
        long actor = BaseContext.getCurrentId();
        String reason = request.getReason().strip();
        var operation = operations.prepare(actor, "RELEASE_MANUAL", key, jobNo + "\n" + reason);
        var job = lock(jobNo);
        if (operation.getResultHttpStatus() != null)
            return operations.replay(operation, RecoveryActionView.class);
        verify(job);
        var day = days.findDay(job.getResourceId(), job.getBookDate());
        if (work.releaseRemaining(job) != job.getEndSlot() - job.getEffectiveStartSlot())
            throw new IllegalStateException("释放格数不一致");
        if (jobs.update(
                        null,
                        new UpdateWrapper<ReleaseJob>()
                                .eq("id", job.getId())
                                .eq("status", "MANUAL_REVIEW")
                                .set("status", "RELEASED")
                                .set("effective_start_slot", job.getEndSlot())
                                .set("last_error", null))
                != 1) throw new IllegalStateException("释放任务更新失败");
        if (days.incrementRevision(job.getResourceId(), job.getBookDate()) != 1)
            throw new IllegalStateException("日历版本更新失败");
        events.record(
                job.getResourceId(), job.getBookDate(), day.getRevision() + 1, clock.instant());
        audit.recordTarget(actor, "RELEASE_MANUAL", jobNo, reason);
        var result = new RecoveryActionView(jobNo, "RELEASED");
        operations.saveSuccess(operation, 200, result);
        return result;
    }

    private ReleaseJob lock(String jobNo) {
        var located = jobs.selectOne(new QueryWrapper<ReleaseJob>().eq("job_no", jobNo));
        if (located == null) {
            admin.lockAndRequireAdmin();
            throw failure(HttpStatus.NOT_FOUND, "RELEASE_NOT_FOUND", "释放任务不存在");
        }
        resources.lockShared(located.getResourceId());
        days.lockDay(located.getResourceId(), located.getBookDate());
        var job = work.lock(located.getId());
        admin.lockAndRequireAdmin();
        return job;
    }

    private void verify(ReleaseJob job) {
        if (!"MANUAL_REVIEW".equals(job.getStatus()))
            throw failure(HttpStatus.CONFLICT, "RELEASE_NOT_MANUAL", "只能恢复待人工处理的释放任务");
        var source = bookings.selectById(job.getSourceBookingId());
        if (source == null
                || !Set.of("CANCELED", "NO_SHOW").contains(source.getStatus())
                || !source.getResourceId().equals(job.getResourceId())
                || !source.getBookDate().equals(job.getBookDate())
                || !source.getStartSlot().equals(job.getStartSlot())
                || !source.getEndSlot().equals(job.getEndSlot()))
            throw failure(HttpStatus.CONFLICT, "RELEASE_OWNERSHIP", "来源订单与释放任务不一致，请先核查数据");
        var slots = work.ownedSlots(job.getId());
        if (slots.size() != job.getEndSlot() - job.getEffectiveStartSlot())
            throw failure(HttpStatus.CONFLICT, "RELEASE_OWNERSHIP", "占用数量与释放任务不一致，请先核查数据");
        for (int index = 0; index < slots.size(); index++) {
            var slot = slots.get(index);
            if (!slot.getResourceId().equals(job.getResourceId())
                    || !slot.getBookDate().equals(job.getBookDate())
                    || !"HANDOFF".equals(slot.getState())
                    || slot.getBookingId() != null
                    || slot.getSlotIndex() != job.getEffectiveStartSlot() + index)
                throw failure(HttpStatus.CONFLICT, "RELEASE_OWNERSHIP", "时间格归属异常，请先核查数据");
        }
    }

    private BusinessException failure(HttpStatus status, String code, String message) {
        return new BusinessException(status, code, message);
    }
}
