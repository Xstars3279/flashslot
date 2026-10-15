package com.flashslot;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.flashslot.messaging.entity.*;
import com.flashslot.messaging.mapper.ConsumerInboxMapper;
import com.flashslot.messaging.model.NotificationEvent;
import com.flashslot.messaging.service.NotificationConsumerService;
import com.flashslot.messaging.service.OutboxDeliveryService;
import com.flashslot.messaging.service.OutboxPublisherService;
import com.flashslot.support.TestUser;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 临时 RabbitMQ + 真实 MySQL，验证从业务事件到通知及消费防重的完整链路。 */
@EnabledIfEnvironmentVariable(named = "FLASHSLOT_PRIVATE_RABBIT_TEST", matches = "true")
@SpringBootTest(
        properties = {
            "app.messaging.enabled=true",
            "app.messaging.scheduling-enabled=false",
            "app.messaging.confirm-timeout-ms=1000"
        })
@AutoConfigureMockMvc
class MessagingDeliveryIntegrationTest {
    static final String EXCHANGE = "flashslot.events",
            QUEUE = "flashslot.notification",
            DEAD = "flashslot.notification.dead";
    @Autowired OutboxPublisherService publisher;
    @Autowired OutboxDeliveryService delivery;
    @MockitoSpyBean RabbitTemplate rabbit;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockitoBean Clock clock;
    @MockitoSpyBean ConsumerInboxMapper inbox;
    @MockitoSpyBean NotificationConsumerService consumer;
    String owner;
    long userId;
    final Instant now = Instant.parse("2026-10-15T00:00:00Z");

    @BeforeEach
    void fixtures() {
        when(clock.instant()).thenReturn(now);
        owner = "mq_" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,role) VALUES (?,'fixture','USER')",
                owner);
        userId = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, owner);
    }

    private boolean publish(String id) {
        return publisher.publish(id);
    }

    private OutboxEvent claim(String id) {
        return delivery.claim(id);
    }

    private boolean markPublished(String id, String token) {
        return delivery.markPublished(id, token);
    }

    private String event(String type) {
        String id = UUID.randomUUID().toString();
        var data =
                json.createObjectNode()
                        .put("orderNo", "BK_" + UUID.randomUUID())
                        .put("source", "DIRECT");
        var payload =
                json.createObjectNode()
                        .put("schemaVersion", 1)
                        .put("userId", userId)
                        .set("data", data);
        jdbc.update(
                "INSERT INTO outbox_event(event_id,event_type,aggregate_id,payload,next_run_at)"
                    + " VALUES (?,?,?,?,?)",
                id,
                type,
                data.get("orderNo").asText(),
                payload.toString(),
                java.sql.Timestamp.from(now));
        return id;
    }

    private Message message(String id) throws Exception {
        String payload =
                jdbc.queryForObject(
                        "SELECT CAST(payload AS CHAR) FROM outbox_event WHERE event_id=?",
                        String.class,
                        id);
        String type =
                jdbc.queryForObject(
                        "SELECT event_type FROM outbox_event WHERE event_id=?", String.class, id);
        String aggregate =
                jdbc.queryForObject(
                        "SELECT aggregate_id FROM outbox_event WHERE event_id=?", String.class, id);
        var body =
                json.createObjectNode()
                        .put("eventId", id)
                        .put("eventType", type)
                        .put("aggregateId", aggregate)
                        .put("occurredAt", now.toString());
        body.set("payload", json.readTree(payload));
        return MessageBuilder.withBody(json.writeValueAsBytes(body))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(id)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }

    private int count(String table, String id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE event_id=?", Integer.class, id);
    }

    private String state(String id) {
        return jdbc.queryForObject(
                "SELECT status FROM outbox_event WHERE event_id=?", String.class, id);
    }

    private void received(String id) {
        await().atMost(Duration.ofSeconds(12))
                .untilAsserted(
                        () -> {
                            assertThat(count("notification", id)).isEqualTo(1);
                            assertThat(count("consumer_inbox", id)).isEqualTo(1);
                        });
    }

    private Binding bookingBinding() {
        return new Binding(
                QUEUE, Binding.DestinationType.QUEUE, EXCHANGE, "booking.created", Map.of());
    }

    @Test
    void realBookingPublishesAndRepeatedDeliveryDoesNotDuplicateNotification() throws Exception {
        var completed = new CountDownLatch(6);
        var target =
                org.springframework.test.util.AopTestUtils
                        .<NotificationConsumerService>getUltimateTargetObject(consumer);
        String name = "mq-device_" + UUID.randomUUID();
        jdbc.update("INSERT INTO resource(name,location) VALUES (?,'A201')", name);
        long resource =
                jdbc.queryForObject("SELECT id FROM resource WHERE name=?", Long.class, name);
        jdbc.update(
                "INSERT INTO resource_day(resource_id,book_date) VALUES (?,'2026-10-16')",
                resource);
        var response =
                mvc.perform(
                                post("/api/v1/bookings")
                                        .with(new TestUser(jdbc, owner))
                                        .with(csrf())
                                        .header("Idempotency-Key", "mq-booking")
                                        .contentType("application/json")
                                        .content(
                                                "{\"resourceId\":"
                                                        + resource
                                                        + ",\"bookDate\":\"2026-10-16\",\"startSlot\":18,\"endSlot\":20,\"purpose\":\"STUDY\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String order =
                json.readTree(response.getResponse().getContentAsString())
                        .at("/data/orderNo")
                        .asText();
        String id =
                jdbc.queryForObject(
                        "SELECT event_id FROM outbox_event WHERE aggregate_id=? AND"
                            + " event_type='BOOKING_CREATED'",
                        String.class,
                        order);
        doAnswer(
                        call -> {
                            try {
                                return call.callRealMethod();
                            } finally {
                                completed.countDown();
                            }
                        })
                .when(target)
                .consume(argThat(event -> event != null && id.equals(event.eventId())));
        assertThat(publish(id)).isTrue();
        received(id);
        assertThat(state(id)).isEqualTo("PUBLISHED");
        for (int i = 0; i < 5; i++) rabbit.send(EXCHANGE, "booking.created", message(id));
        assertThat(completed.await(12, TimeUnit.SECONDS)).isTrue();
        assertThat(count("consumer_inbox", id)).isEqualTo(1);
        assertThat(count("notification", id)).isEqualTo(1);
        mvc.perform(get("/api/v1/me/notifications").with(new TestUser(jdbc, owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items.length()").value(1))
                .andExpect(jsonPath("data.items[0].orderNo").value(order));
    }

    @Test
    void unroutableMessageIsNotMarkedPublishedAndCanRetryAfterBindingRestored() throws Exception {
        String id = event("BOOKING_CREATED");
        RabbitAdmin admin = new RabbitAdmin(rabbit.getConnectionFactory());
        admin.removeBinding(bookingBinding());
        try {
            assertThat(publish(id)).isFalse();
            assertThat(state(id)).isEqualTo("NEW");
            assertThat(count("notification", id)).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT last_error FROM outbox_event WHERE event_id=?",
                                    String.class,
                                    id))
                    .isEqualTo("BROKER_RETURN");
        } finally {
            admin.declareBinding(bookingBinding());
        }
        when(clock.instant()).thenReturn(now.plusSeconds(2));
        assertThat(publish(id)).isTrue();
        received(id);
    }

    @Test
    void crashAfterBrokerDeliveryRecoversExpiredLeaseWithoutDuplicateNotice() throws Exception {
        String id = event("BOOKING_CREATED");
        OutboxEvent first = claim(id);
        rabbit.send(EXCHANGE, "booking.created", message(id));
        received(id);
        jdbc.update(
                "UPDATE outbox_event SET lease_until=? WHERE event_id=?",
                java.sql.Timestamp.from(now.minusSeconds(1)),
                id);
        assertThat(publish(id)).isTrue();
        assertThat(state(id)).isEqualTo("PUBLISHED");
        assertThat(markPublished(id, first.getLeaseToken())).isFalse();
        assertThat(count("notification", id)).isEqualTo(1);
    }

    @Test
    void oldLeaseCannotOverwriteAnotherPublishAttempt() throws Exception {
        String id = event("BOOKING_CREATED");
        OutboxEvent first = claim(id);
        assertThat(claim(id)).isNull();
        jdbc.update(
                "UPDATE outbox_event SET lease_until=? WHERE event_id=?",
                java.sql.Timestamp.from(now.minusSeconds(1)),
                id);
        OutboxEvent second = claim(id);
        assertThat(first.getLeaseToken()).isNotEqualTo(second.getLeaseToken());
        assertThat(markPublished(id, first.getLeaseToken())).isFalse();
        assertThat(state(id)).isEqualTo("SENDING");
        assertThat(delivery.recordFailure(id, first.getLeaseToken(), "STALE_FAILURE")).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT last_error FROM outbox_event WHERE event_id=?",
                                String.class,
                                id))
                .isNull();
        assertThat(markPublished(id, second.getLeaseToken())).isTrue();
    }

    @Test
    void consumerFailureRollsBackInboxAndNotificationThenDeadLetterCanBeRedelivered()
            throws Exception {
        RabbitAdmin admin = new RabbitAdmin(rabbit.getConnectionFactory());
        admin.purgeQueue(DEAD);
        String id = event("BOOKING_CREATED");
        doThrow(
                        new IllegalStateException(
                                "private injected inbox failure after notification insert"))
                .when(inbox)
                .complete(id);
        assertThat(publish(id)).isTrue();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(admin.getQueueInfo(DEAD).getMessageCount())
                                        .isGreaterThanOrEqualTo(1));
        assertThat(count("notification", id)).isZero();
        assertThat(count("consumer_inbox", id)).isZero();
        reset(inbox);
        rabbit.send(EXCHANGE, "booking.created", rabbit.receive(DEAD));
        received(id);
    }

    @Test
    void unsupportedSchemaGoesToDeadLetterWithoutCreatingNotice() throws Exception {
        RabbitAdmin admin = new RabbitAdmin(rabbit.getConnectionFactory());
        admin.purgeQueue(DEAD);
        String id = event("BOOKING_CREATED");
        var malformed = json.readTree(message(id).getBody());
        ((com.fasterxml.jackson.databind.node.ObjectNode) malformed.get("payload"))
                .put("schemaVersion", 2);
        rabbit.send(
                EXCHANGE,
                "booking.created",
                MessageBuilder.withBody(json.writeValueAsBytes(malformed))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .build());
        await().atMost(Duration.ofSeconds(12))
                .untilAsserted(
                        () ->
                                assertThat(admin.getQueueInfo(DEAD).getMessageCount())
                                        .isGreaterThanOrEqualTo(1));
        assertThat(count("notification", id)).isZero();
        assertThat(count("consumer_inbox", id)).isZero();
    }

    @Test
    void nonNotificationEventsAreNotFalselyMarkedPublished() throws Exception {
        String id = event("SCHEDULE_CHANGED");
        assertThat(publish(id)).isFalse();
        assertThat(state(id)).isEqualTo("NEW");
        assertThat(count("notification", id)).isZero();
    }

    @Test
    void scannerSelectsDueEventsAndLeavesFutureAndUnsupportedEventsAlone() {
        String due = event("BOOKING_CREATED"),
                future = event("BOOKING_CREATED"),
                unsupported = event("SCHEDULE_CHANGED");
        jdbc.update(
                "UPDATE outbox_event SET next_run_at='2000-01-01' WHERE event_id IN (?,?)",
                due,
                unsupported);
        jdbc.update("UPDATE outbox_event SET next_run_at='2099-01-01' WHERE event_id=?", future);
        assertThat(publisher.scanDueEvents()).isPositive();
        received(due);
        assertThat(state(due)).isEqualTo("PUBLISHED");
        assertThat(state(future)).isEqualTo("NEW");
        assertThat(state(unsupported)).isEqualTo("NEW");
    }

    @Test
    void twelvePublishFailuresEnterFailedState() throws Exception {
        String id = event("BOOKING_CREATED");
        RabbitAdmin admin = new RabbitAdmin(rabbit.getConnectionFactory());
        admin.removeBinding(bookingBinding());
        try {
            for (int i = 0; i < 12; i++) {
                when(clock.instant()).thenReturn(now.plusSeconds(i * 1000L));
                assertThat(publish(id)).isFalse();
            }
        } finally {
            admin.declareBinding(bookingBinding());
        }
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempts FROM outbox_event WHERE event_id=?",
                                Integer.class,
                                id))
                .isEqualTo(12);
    }

    @Test
    void brokerNackKeepsEventPendingForRetry() {
        String id = event("BOOKING_CREATED");
        doAnswer(
                        call -> {
                            CorrelationData correlation = call.getArgument(3);
                            correlation
                                    .getFuture()
                                    .complete(new CorrelationData.Confirm(false, "injected nack"));
                            return null;
                        })
                .when(rabbit)
                .send(
                        eq(EXCHANGE),
                        eq("booking.created"),
                        any(Message.class),
                        any(CorrelationData.class));
        assertThat(publish(id)).isFalse();
        assertThat(state(id)).isEqualTo("NEW");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT last_error FROM outbox_event WHERE event_id=?",
                                String.class,
                                id))
                .isEqualTo("BROKER_NACK");
        assertThat(count("notification", id)).isZero();
    }

    @Test
    void confirmTimeoutDoesNotMarkEventPublished() {
        String id = event("BOOKING_CREATED");
        doNothing()
                .when(rabbit)
                .send(
                        eq(EXCHANGE),
                        eq("booking.created"),
                        any(Message.class),
                        any(CorrelationData.class));
        assertThat(publish(id)).isFalse();
        assertThat(state(id)).isEqualTo("NEW");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT last_error FROM outbox_event WHERE event_id=?",
                                String.class,
                                id))
                .isEqualTo("CONFIRM_TIMEOUT");
    }

    @Test
    void concurrentConsumersCommitOneNoticeAndOneInboxRecord() throws Exception {
        String id = event("BOOKING_CREATED");
        NotificationEvent envelope = json.readValue(message(id).getBody(), NotificationEvent.class);
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first =
                    pool.submit(
                            () -> {
                                gate.await();
                                consumer.consume(envelope);
                                return true;
                            });
            var second =
                    pool.submit(
                            () -> {
                                gate.await();
                                consumer.consume(envelope);
                                return true;
                            });
            gate.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("notification", id)).isEqualTo(1);
        assertThat(count("consumer_inbox", id)).isEqualTo(1);
    }

    @Test
    void waitlistBookingIsSilentButPromotionEventCreatesOneNotice() throws Exception {
        String created = event("BOOKING_CREATED"), matched = event("WAITLIST_MATCHED");
        jdbc.update(
                "UPDATE outbox_event SET payload=JSON_SET(payload,'$.data.source','WAITLIST') WHERE"
                    + " event_id=?",
                created);
        assertThat(publish(created)).isTrue();
        await().atMost(Duration.ofSeconds(8))
                .untilAsserted(() -> assertThat(count("consumer_inbox", created)).isEqualTo(1));
        assertThat(count("notification", created)).isZero();
        assertThat(publish(matched)).isTrue();
        received(matched);
        mvc.perform(get("/api/v1/me/notifications").with(new TestUser(jdbc, owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items.length()").value(1))
                .andExpect(jsonPath("data.items[0].title").value("候补成功"));
    }
}
