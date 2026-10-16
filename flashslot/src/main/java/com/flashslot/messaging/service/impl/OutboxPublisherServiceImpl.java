package com.flashslot.messaging.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.messaging.entity.OutboxEvent;
import com.flashslot.messaging.mapper.OutboxEventMapper;
import com.flashslot.messaging.model.*;
import com.flashslot.messaging.service.*;

import lombok.extern.slf4j.Slf4j;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.concurrent.*;

/** 领取事务先提交，再发送消息；只有 ACK 且无 return 才记录 PUBLISHED。 */
@Service
@Slf4j
@ConditionalOnProperty(
        prefix = "app.messaging",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OutboxPublisherServiceImpl implements OutboxPublisherService {
    @Autowired private OutboxDeliveryService delivery;
    @Autowired private OutboxEventMapper mapper;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private ObjectMapper json;
    @Autowired private Clock clock;

    @Value("${app.messaging.confirm-timeout-ms:5000}")
    private long confirmTimeout;

    @Override
    public int scanDueEvents() {
        int sent = 0;
        long started = System.nanoTime();
        for (String id : mapper.findDue(clock.instant(), NotificationEventType.names())) {
            if (Thread.currentThread().isInterrupted()
                    || System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(10)) break;
            try {
                if (publish(id)) sent++;
            } catch (RuntimeException ex) {
                log.warn("消息投递未完成，eventId={}，异常类型={}", id, ex.getClass().getSimpleName());
            }
        }
        return sent;
    }

    @Override
    public boolean publish(String eventId) {
        OutboxEvent event = delivery.claim(eventId);
        if (event == null) return false;
        String reason = "PUBLISH_FAILED";
        try {
            var envelope =
                    new NotificationEvent(
                            event.getEventId(),
                            event.getEventType(),
                            event.getAggregateId(),
                            event.getCreatedAt(),
                            event.getPayload());
            envelope.validate();
            var message =
                    MessageBuilder.withBody(json.writeValueAsBytes(envelope))
                            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                            .setMessageId(event.getEventId())
                            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                            .build();
            var correlation = new CorrelationData(event.getEventId() + ":" + event.getLeaseToken());
            rabbit.send(
                    "flashslot.events",
                    NotificationEventType.valueOf(event.getEventType()).getRoutingKey(),
                    message,
                    correlation);
            var confirm = correlation.getFuture().get(confirmTimeout, TimeUnit.MILLISECONDS);
            if (correlation.getReturned() != null) reason = "BROKER_RETURN";
            else if (!confirm.isAck()) reason = "BROKER_NACK";
            else return delivery.markPublished(eventId, event.getLeaseToken());
        } catch (TimeoutException ex) {
            reason = "CONFIRM_TIMEOUT";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            reason = "PUBLISH_INTERRUPTED";
        } catch (IllegalArgumentException ex) {
            reason = "INVALID_EVENT";
        } catch (Exception ex) {
            reason = ex.getClass().getSimpleName();
        }
        // 失败记录也使用独立事务；旧租约的迟到结果不能覆盖新的发送尝试。
        delivery.recordFailure(eventId, event.getLeaseToken(), reason);
        return false;
    }
}
