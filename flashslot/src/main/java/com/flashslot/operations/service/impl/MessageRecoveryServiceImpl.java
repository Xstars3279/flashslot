package com.flashslot.operations.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.flashslot.auth.service.CurrentAdmin;
import com.flashslot.common.context.BaseContext;
import com.flashslot.common.service.IdempotentOperationService;
import com.flashslot.common.web.BusinessException;
import com.flashslot.messaging.entity.OutboxEvent;
import com.flashslot.messaging.mapper.OutboxEventMapper;
import com.flashslot.messaging.model.PublishedEvent;
import com.flashslot.operations.dto.RecoveryRequest;
import com.flashslot.operations.service.*;
import com.flashslot.operations.vo.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class MessageRecoveryServiceImpl implements MessageRecoveryService {
    @Autowired private CurrentAdmin admin;
    @Autowired private OutboxEventMapper mapper;
    @Autowired private IdempotentOperationService operations;
    @Autowired private AdminAuditService audit;
    @Autowired private Clock clock;

    @Override
    @Transactional(readOnly = true)
    public RecoveryPage<FailedMessageView> list(String cursor, int limit) {
        admin.requireAdmin();
        if (limit < 1 || limit > 100 || (cursor != null && !cursor.matches("[A-Za-z0-9-]{1,36}")))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "分页参数不合法");
        var query = new QueryWrapper<OutboxEvent>().eq("status", "FAILED");
        if (cursor != null) query.gt("event_id", cursor);
        var found = mapper.selectList(query.orderByAsc("event_id").last("LIMIT " + (limit + 1)));
        var items =
                found.stream()
                        .limit(limit)
                        .map(
                                e ->
                                        new FailedMessageView(
                                                e.getEventId(),
                                                e.getEventType(),
                                                e.getAggregateId(),
                                                e.getAttempts(),
                                                e.getLastError(),
                                                e.getCreatedAt()))
                        .toList();
        boolean more = found.size() > limit;
        return new RecoveryPage<>(items, more ? items.get(items.size() - 1).eventId() : null, more);
    }

    /** 仅重置投递失败的Outbox，保留事件ID，让Inbox继续拦截重复消费。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public RecoveryActionView retry(String eventId, String key, RecoveryRequest request) {
        long actor = BaseContext.getCurrentId();
        String reason = request.getReason().strip();
        var operation = operations.prepare(actor, "MESSAGE_RETRY", key, eventId + "\n" + reason);
        var event = mapper.lock(eventId);
        admin.lockAndRequireAdmin();
        if (event == null)
            throw new BusinessException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "消息不存在");
        if (operation.getResultHttpStatus() != null)
            return operations.replay(operation, RecoveryActionView.class);
        if (!"FAILED".equals(event.getStatus())
                || !PublishedEvent.supported(event.getEventType()))
            throw new BusinessException(
                    HttpStatus.CONFLICT, "EVENT_NOT_RETRYABLE", "只能重试受支持且投递失败的消息");
        if (mapper.update(
                        null,
                        new UpdateWrapper<OutboxEvent>()
                                .eq("event_id", eventId)
                                .eq("status", "FAILED")
                                .set("status", "NEW")
                                .set("attempts", 0)
                                .set("next_run_at", clock.instant())
                                .set("lease_token", null)
                                .set("lease_until", null)
                                .set("last_error", null))
                != 1) throw new IllegalStateException("消息重置失败");
        audit.recordTarget(actor, "MESSAGE_RETRY", eventId, reason);
        var result = new RecoveryActionView(eventId, "NEW");
        operations.saveSuccess(operation, 200, result);
        return result;
    }
}
