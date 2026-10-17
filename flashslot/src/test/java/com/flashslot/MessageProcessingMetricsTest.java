package com.flashslot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.messaging.entity.OutboxEvent;
import com.flashslot.messaging.mapper.ConsumerInboxMapper;
import com.flashslot.messaging.metrics.MessageProcessingMetrics;
import com.flashslot.messaging.model.NotificationEvent;
import com.flashslot.messaging.service.*;
import com.flashslot.messaging.service.impl.*;
import com.flashslot.waitlist.service.ReleaseJobService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;

class MessageProcessingMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MessageProcessingMetrics metrics = new MessageProcessingMetrics(registry);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test void malformedListenerMessagesCountAsFailedAttempts() {
        var notification = new com.flashslot.messaging.listener.NotificationListener();
        ReflectionTestUtils.setField(notification, "json", json);
        ReflectionTestUtils.setField(notification, "metrics", metrics);
        var release = new com.flashslot.messaging.listener.ReleaseListener(json, null);
        ReflectionTestUtils.setField(release, "metrics", metrics);
        var invalid = new org.springframework.amqp.core.Message("invalid-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> notification.receive(invalid)).isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> release.receive(invalid)).isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
        assertConsumer("notification", "failed", 1);
        assertConsumer("release", "failed", 1);
        assertConsumer("notification", "success", 0);
        assertConsumer("release", "success", 0);
    }

    @Test void publisherMeasuresConfirmedFailureAndUnclaimedAttempts() {
        var delivery = mock(OutboxDeliveryService.class);
        var rabbit = mock(RabbitTemplate.class);
        var service = new OutboxPublisherServiceImpl();
        ReflectionTestUtils.setField(service, "delivery", delivery);
        ReflectionTestUtils.setField(service, "rabbit", rabbit);
        ReflectionTestUtils.setField(service, "json", json);
        ReflectionTestUtils.setField(service, "metrics", metrics);
        ReflectionTestUtils.setField(service, "confirmTimeout", 100L);
        var event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("RELEASE_DISPATCHED");
        event.setAggregateId("42");
        event.setCreatedAt(Instant.parse("2026-10-17T00:00:00Z"));
        event.setPayload(json.createObjectNode().put("schemaVersion", 1).put("jobId", 42));
        event.setLeaseToken("lease");
        when(delivery.claim(event.getEventId())).thenReturn(event);
        when(delivery.markPublished(event.getEventId(), "lease")).thenReturn(true);
        doAnswer(call -> {
            call.getArgument(3, CorrelationData.class).getFuture()
                    .complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(), any(CorrelationData.class));
        assertThat(service.publish(event.getEventId())).isTrue();
        doThrow(new IllegalStateException("broker unavailable"))
                .when(rabbit).send(anyString(), anyString(), any(), any(CorrelationData.class));
        assertThat(service.publish(event.getEventId())).isFalse();
        assertThat(service.publish("unclaimed")).isFalse();
        for (String result : new String[] {"published", "failed", "skipped"}) {
            assertThat(registry.get("flashslot.message.publish.attempts").tag("result", result).counter().count()).isEqualTo(1);
            assertThat(registry.get("flashslot.message.publish.duration").tag("result", result).timer().count()).isEqualTo(1);
        }
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).noneMatch(tag -> tag.getKey().equals("eventId") || tag.getKey().equals("userId")));
    }

    @Test void releaseRetriesCountAttemptsAndDeduplicatedRedeliveryCountsSuccess() {
        var jobs = mock(ReleaseJobService.class);
        var inbox = mock(ConsumerInboxMapper.class);
        var service = new ReleaseConsumerService(jobs, inbox, metrics);
        var event = new NotificationEvent(UUID.randomUUID().toString(), "RELEASE_DISPATCHED", "42", Instant.now(),
                json.createObjectNode().put("schemaVersion", 1).put("jobId", 42));
        doThrow(new IllegalStateException("retry" )).doNothing().when(jobs).processMessage(42L);
        assertThatThrownBy(() -> service.consume(event)).isInstanceOf(IllegalStateException.class);
        service.consume(event);
        when(inbox.releaseCompleted(event.eventId())).thenReturn(1);
        service.consume(event);
        assertConsumer("release", "failed", 1);
        assertConsumer("release", "success", 2);
    }

    @Test void notificationWaitsForCommitAndRecordsRollbackAsFailure() {
        var service = new NotificationConsumerServiceImpl();
        var accounts = mock(AccountPermissionMapper.class);
        ReflectionTestUtils.setField(service, "accounts", accounts);
        ReflectionTestUtils.setField(service, "inbox", mock(ConsumerInboxMapper.class));
        ReflectionTestUtils.setField(service, "metrics", metrics);
        var event = new NotificationEvent(UUID.randomUUID().toString(), "BOOKING_CREATED", "order", Instant.now(),
                json.createObjectNode().put("schemaVersion", 1).put("userId", 1).set("data", json.createObjectNode()));
        // An invalid user fails inside the real consumer before notification persistence.
        assertThatThrownBy(() -> service.consume(event)).isInstanceOf(IllegalArgumentException.class);
        assertConsumer("notification", "failed", 1);
        // Duplicate deliveries still enter a real transaction; commit failures must not report success.
        when(accounts.lockBookingAccount(1L)).thenReturn(new com.flashslot.auth.entity.AppUser());
        var inbox = mock(ConsumerInboxMapper.class);
        when(inbox.completed(event.eventId())).thenReturn(1);
        ReflectionTestUtils.setField(service, "inbox", inbox);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.consume(event);
            assertConsumer("notification", "success", 0);
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        assertConsumer("notification", "failed", 2);
        service.consume(event);
        assertConsumer("notification", "success", 1);
    }

    private void assertConsumer(String channel, String result, long count) {
        assertThat(registry.get("flashslot.message.consume.attempts").tags("channel", channel, "result", result).counter().count()).isEqualTo(count);
        assertThat(registry.get("flashslot.message.consume.duration").tags("channel", channel, "result", result).timer().count()).isEqualTo(count);
    }
}
