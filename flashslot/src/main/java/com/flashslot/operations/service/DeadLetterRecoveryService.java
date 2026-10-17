package com.flashslot.operations.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.auth.service.CurrentAdmin;
import com.flashslot.common.context.BaseContext;
import com.flashslot.common.service.IdempotentOperationService;
import com.flashslot.common.web.BusinessException;
import com.flashslot.messaging.entity.DeadLetter;
import com.flashslot.messaging.mapper.DeadLetterMapper;
import com.flashslot.messaging.model.NotificationEvent;
import com.flashslot.messaging.model.PublishedEvent;
import com.flashslot.messaging.entity.OutboxEvent;
import com.flashslot.messaging.mapper.OutboxEventMapper;
import com.flashslot.messaging.service.NotificationConsumerService;
import com.flashslot.operations.dto.RecoveryRequest;
import com.flashslot.operations.vo.*;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class DeadLetterRecoveryService {
    private final DeadLetterMapper mapper;
    private final CurrentAdmin admin;
    private final AccountPermissionMapper accounts;
    private final IdempotentOperationService operations;
    private final NotificationConsumerService consumer;
    private final AdminAuditService audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final OutboxEventMapper outbox;
    public record DeadLetterView(long id,String eventId,String eventType,String errorCode,String status,int deliveries,Instant receivedAt) {}

    @Transactional(readOnly=true)
    public RecoveryPage<DeadLetterView> list(long cursor,int limit) {
        admin.requireAdmin();
        if(cursor<0 || limit<1 || limit>100) throw failure(HttpStatus.BAD_REQUEST,"分页参数不合法");
        var rows=mapper.selectList(new QueryWrapper<DeadLetter>().select("id","event_id","event_type","error_code","status","deliveries","received_at")
            .eq("status","FAILED").gt("id",cursor).orderByAsc("id").last("LIMIT "+(limit+1)));
        var items=rows.stream().limit(limit).map(r -> new DeadLetterView(r.getId(),r.getEventId(),r.getEventType(),r.getErrorCode(),r.getStatus(),r.getDeliveries(),r.getReceivedAt())).toList();
        boolean more=rows.size()>limit;
        return new RecoveryPage<>(items,more?Long.toString(items.getLast().id()):null,more);
    }

    /** 原事件按现有消费逻辑执行，与 Inbox、通知、审计和恢复状态一起提交或回滚。 */
    @Transactional(rollbackFor=Exception.class)
    public RecoveryActionView replay(long id,String key,RecoveryRequest request) {
        long actor=BaseContext.getCurrentId();
        String reason=request.getReason().strip();
        var operation=operations.prepare(actor,"DEAD_LETTER_REPLAY",key,id+"\n"+reason);
        var letter=mapper.lock(id);
        // 先做无锁权限核对，不能向非当前管理员透露消息格式。
        admin.requireAdmin();
        if(letter==null) throw failure(HttpStatus.NOT_FOUND,"消费死信不存在");
        NotificationEvent event;
        try { event=json.readValue(letter.getBody(),NotificationEvent.class); PublishedEvent.validate(event); }
        catch(Exception ex) { throw failure(HttpStatus.CONFLICT,"消息格式或版本不支持，需修复原因后再恢复"); }
        // 撮合由 MQ 执行，恢复事务只可靠重排消息，避免持有管理员锁再进入资源/日期锁。
        if(PublishedEvent.RELEASE.equals(event.eventType())) {
            var existing=outbox.lock(event.eventId());
            admin.lockAndRequireAdmin();
            if(operation.getResultHttpStatus()!=null) return operations.replay(operation,RecoveryActionView.class);
            if(!"RECOVERED".equals(letter.getStatus())) {
                if(existing==null || !event.eventType().equals(existing.getEventType())
                    || !event.aggregateId().equals(existing.getAggregateId()) || !event.payload().equals(existing.getPayload()))
                    throw failure(HttpStatus.CONFLICT,"找不到一致的原始撮合事件，请先核对任务");
                if("SENDING".equals(existing.getStatus()) && existing.getLeaseUntil()!=null && existing.getLeaseUntil().isAfter(clock.instant()))
                    throw failure(HttpStatus.CONFLICT,"原事件正在投递，请稍后恢复");
                outbox.update(null,new UpdateWrapper<OutboxEvent>().eq("event_id",event.eventId())
                    .set("status","NEW").set("attempts",0).set("next_run_at",clock.instant())
                    .set("lease_token",null).set("lease_until",null).set("last_error",null).set("published_at",null));
                mapper.update(null,new UpdateWrapper<DeadLetter>().eq("id",id).set("status","RECOVERED").set("recovered_at",clock.instant()));
                audit.recordTarget(actor,"DEAD_LETTER_REPLAY",Long.toString(id),reason);
            }
            var result=new RecoveryActionView(Long.toString(id),"RECOVERED");
            operations.saveSuccess(operation,200,result);
            return result;
        }
        long target=event.payload().path("userId").asLong();
        accounts.lockBookingAccount(Math.min(actor,target));
        if(actor!=target) accounts.lockBookingAccount(Math.max(actor,target));
        admin.lockAndRequireAdmin();
        if(operation.getResultHttpStatus()!=null) return operations.replay(operation,RecoveryActionView.class);
        if(!"RECOVERED".equals(letter.getStatus())) {
            consumer.consume(event);
            mapper.update(null,new UpdateWrapper<DeadLetter>().eq("id",id).set("status","RECOVERED").set("recovered_at",clock.instant()));
            audit.recordTarget(actor,"DEAD_LETTER_REPLAY",Long.toString(id),reason);
        }
        var result=new RecoveryActionView(Long.toString(id),"RECOVERED");
        operations.saveSuccess(operation,200,result);
        return result;
    }
    private BusinessException failure(HttpStatus status,String message) { return new BusinessException(status,"DEAD_LETTER_RECOVERY",message); }
}
