package com.flashslot;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.messaging.mapper.*;
import com.flashslot.messaging.model.NotificationEvent;
import com.flashslot.messaging.service.*;
import com.flashslot.operations.mapper.AdminAuditMapper;
import com.flashslot.support.TestUser;
import com.flashslot.waitlist.service.ReleaseDispatchService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;

@EnabledIfEnvironmentVariable(named="FLASHSLOT_PRIVATE_RABBIT_TEST",matches="true")
@SpringBootTest(properties={"app.messaging.enabled=true","app.messaging.scheduling-enabled=false","app.messaging.confirm-timeout-ms=1000"})
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ReleaseDispatchIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ReleaseDispatchService dispatch;
    @Autowired com.flashslot.waitlist.service.WaitlistMatchingService matching;
    @Autowired OutboxPublisherService publisher;
    @Autowired RabbitTemplate rabbit;
    @MockitoSpyBean ReleaseConsumerService consumer;
    @MockitoSpyBean ConsumerInboxMapper inbox;
    @MockitoSpyBean AdminAuditMapper audit;
    @MockitoSpyBean OutboxEventMapper outbox;
    @MockitoBean Clock clock;
    final Instant now=Instant.parse("2026-10-15T00:00:00Z");
    String owner,candidate,admin,order;
    long device,job;

    String account(String role) {
        String name="dispatch_"+UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?,'fixture',?)",name,role);
        return name;
    }
    @BeforeEach void fixtures() throws Exception {
        when(clock.instant()).thenReturn(now);
        owner=account("USER"); candidate=account("USER"); admin=account("ADMIN");
        String name="dispatch_device_"+UUID.randomUUID();
        jdbc.update("INSERT INTO resource(name,location) VALUES (?,'A201')",name);
        device=jdbc.queryForObject("SELECT id FROM resource WHERE name=?",Long.class,name);
        jdbc.update("INSERT INTO resource_day(resource_id,book_date) VALUES (?,'2026-10-16')",device);
        order=interval("bookings",owner,"orderNo");
    }
    String interval(String path,String who,String field) throws Exception {
        var result=mvc.perform(post("/api/v1/"+path).with(new TestUser(jdbc,who)).with(csrf())
            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
            .content("{\"resourceId\":"+device+",\"bookDate\":\"2026-10-16\",\"startSlot\":18,\"endSlot\":22,\"purpose\":\"STUDY\"}"))
            .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).at("/data/"+field).asText();
    }
    void cancel(boolean waiting) throws Exception {
        if(waiting) interval("waitlists",candidate,"waitlistNo");
        mvc.perform(post("/api/v1/bookings/"+order+"/cancel").with(new TestUser(jdbc,owner)).with(csrf())
            .header("Idempotency-Key","cancel").contentType("application/json").content("{}"))
            .andExpect(status().isOk());
        job=jdbc.queryForObject("SELECT id FROM release_job WHERE resource_id=?",Long.class,device);
    }
    String state() { return jdbc.queryForObject("SELECT status FROM release_job WHERE id=?",String.class,job); }
    int orders() { return jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE release_job_id=?",Integer.class,job); }
    String eventId() {
        return jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE event_type='RELEASE_DISPATCHED' AND aggregate_id=? ORDER BY created_at DESC LIMIT 1",String.class,Long.toString(job));
    }
    NotificationEvent event(String id) {
        var row=outbox.selectById(id);
        return new NotificationEvent(id,row.getEventType(),row.getAggregateId(),row.getCreatedAt(),row.getPayload());
    }
    void received(String id) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(state()).isEqualTo("MATCHED");
            assertThat(inbox.releaseCompleted(id)).isEqualTo(1);
            assertThat(orders()).isEqualTo(1);
        });
    }
    void replay(long letter,String key,int expected) throws Exception {
        mvc.perform(post("/api/v1/admin/dead-letters/"+letter+"/replay").with(new TestUser(jdbc,admin).roles("ADMIN")).with(csrf())
            .header("Idempotency-Key",key).contentType("application/json").content("{\"reason\":\"依赖故障已修复\"}"))
            .andExpect(status().is(expected));
    }

    @Test void producerPreservesHalfHourAndConcurrentScansCreateOneEvent() throws Exception {
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        cancel(true);
        dispatch.scan();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='RELEASE_DISPATCHED' AND aggregate_id=?",Integer.class,Long.toString(job))).isZero();
        when(clock.instant()).thenReturn(now.plusSeconds(1800));
        var pool=Executors.newFixedThreadPool(4);
        try {
            var calls=new ArrayList<Future<Integer>>();
            for(int i=0;i<4;i++) calls.add(pool.submit(dispatch::scan));
            for(var call:calls) call.get(20,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        dispatch.scan();
        assertThat(state()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='RELEASE_DISPATCHED' AND aggregate_id=?",Integer.class,Long.toString(job))).isEqualTo(1);
        assertThat(publisher.publish(eventId())).isTrue();
        received(eventId());
    }

    @Test void rabbitDuplicatesCreateOnePromotionAndOneBusinessEvent() throws Exception {
        cancel(true); dispatch.scan(); String id=eventId();
        assertThat(publisher.publish(id)).isTrue();
        byte[] body=json.writeValueAsBytes(event(id));
        for(int i=0;i<6;i++) rabbit.send("flashslot.events","release.dispatch",MessageBuilder.withBody(body).build());
        received(id);
        consumer.consume(event(id));
        assertThat(orders()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='WAITLIST_MATCHED' AND payload->>'$.userId'=(SELECT id FROM app_user WHERE username=?)",Integer.class,candidate)).isEqualTo(1);
    }

    @Test void crashAfterBusinessCommitBeforeInboxIsSafeToResume() throws Exception {
        cancel(true); dispatch.scan(); String id=eventId();
        doThrow(new IllegalStateException("crash before inbox")).when(inbox).completeRelease(id);
        assertThatThrownBy(() -> consumer.consume(event(id))).isInstanceOf(IllegalStateException.class);
        assertThat(state()).isEqualTo("MATCHED"); assertThat(orders()).isEqualTo(1);
        reset(inbox);
        consumer.consume(event(id)); consumer.consume(event(id));
        assertThat(orders()).isEqualTo(1); assertThat(inbox.releaseCompleted(id)).isEqualTo(1);
    }

    @Test void concurrentConsumersAndNoCandidateReleaseAreIdempotent() throws Exception {
        cancel(true);
        // 取消时有候补而保留交接，派发前候补自行取消，验证消费并发释放路径。
        jdbc.update("UPDATE waitlist_record SET status='CANCELED' WHERE resource_id=?",device);
        dispatch.scan(); String id=eventId(); var event=event(id);
        var pool=Executors.newFixedThreadPool(6);
        try {
            var calls=new ArrayList<Future<?>>();
            for(int i=0;i<6;i++) calls.add(pool.submit(() -> consumer.consume(event)));
            for(var call:calls) call.get(20,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(state()).isEqualTo("RELEASED"); assertThat(orders()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM slot_occupation WHERE release_job_id=?",Integer.class,job)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='RELEASE_COMPLETED' AND aggregate_id=(SELECT job_no FROM release_job WHERE id=?)",Integer.class,job)).isEqualTo(1);
    }

    @Test void duplicateFailuresCountOneRetryRoundInsteadOfPrematureManualReview() throws Exception {
        cancel(true);
        for(int i=0;i<6;i++) matching.recordFailure(job,new IllegalStateException("same round"));
        assertThat(state()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM release_job WHERE id=?",Integer.class,job)).isEqualTo(1);
    }

    @Test void compensationDoesNotStarveTasksBehindAnAlreadyDispatchedBatch() throws Exception {
        cancel(true);
        var sources=new ArrayList<String>();
        for(int i=0;i<51;i++) {
            String source="BK_"+UUID.randomUUID(); sources.add(source);
            jdbc.update("""
                INSERT INTO booking_order(order_no,user_id,resource_id,book_date,start_slot,end_slot,charged_slots,
                  status,purpose,start_at,check_in_open_at,check_in_deadline,end_at,terminated_at,termination_reason)
                SELECT ?,user_id,resource_id,book_date,start_slot,end_slot,charged_slots,status,purpose,
                  start_at,check_in_open_at,check_in_deadline,end_at,terminated_at,termination_reason
                FROM booking_order WHERE order_no=?
                """,source,order);
            jdbc.update("""
                INSERT INTO release_job(job_no,source_booking_id,resource_id,book_date,start_slot,end_slot,effective_start_slot,next_run_at)
                SELECT ?,id,resource_id,book_date,start_slot,end_slot,start_slot,? FROM booking_order WHERE order_no=?
                ""","RJ_"+UUID.randomUUID(),java.sql.Timestamp.from(now),source);
        }
        // 不执行撮合，模拟 broker 暂时不可用，队头一直 PENDING 也不能挡住后续派发。
        for(int i=0;i<5;i++) dispatch.scan();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM release_job j JOIN outbox_event e
              ON e.aggregate_id=CAST(j.id AS CHAR) AND e.event_type='RELEASE_DISPATCHED'
            WHERE j.resource_id=?
            """,Integer.class,device)).isEqualTo(52);
        jdbc.update("UPDATE release_job SET status='MANUAL_REVIEW' WHERE resource_id=?",device);
    }

    @Test void failedTransactionRollsBackAndDatabaseCompensationDispatchesNextRound() throws Exception {
        cancel(true); dispatch.scan(); String original=eventId();
        doThrow(new IllegalStateException("private rollback")).when(outbox).insert(
            argThat((com.flashslot.messaging.entity.OutboxEvent e) -> e!=null && "WAITLIST_MATCHED".equals(e.getEventType())));
        assertThat(publisher.publish(original)).isTrue();
        await().atMost(Duration.ofSeconds(18)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT attempts FROM release_job WHERE id=?",Integer.class,job)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dead_letter WHERE event_id=?",Integer.class,original)).isEqualTo(1);
        });
        assertThat(inbox.releaseCompleted(original)).isZero();
        assertThat(state()).isEqualTo("PENDING"); assertThat(orders()).isZero();
        assertThat(jdbc.queryForObject("SELECT attempts FROM release_job WHERE id=?",Integer.class,job)).isEqualTo(1);
        reset(outbox);
        when(clock.instant()).thenReturn(now.plusSeconds(1800));
        dispatch.scan(); String next=eventId(); assertThat(next).isNotEqualTo(original);
        assertThat(publisher.publish(next)).isTrue(); received(next);
        consumer.consume(event(original)); assertThat(orders()).isEqualTo(1);
    }

    @Test void failedReleaseIsPersistedWithoutDependingOnDeadLetterRouting() throws Exception {
        cancel(true); dispatch.scan(); String id=eventId();
        var failedEvent=event(id);
        doThrow(new IllegalStateException("release consumer down")).when(consumer).consume(failedEvent);
        var broker=new org.springframework.amqp.rabbit.core.RabbitAdmin(rabbit.getConnectionFactory());
        var binding=new org.springframework.amqp.core.Binding("flashslot.release.dead",
            org.springframework.amqp.core.Binding.DestinationType.QUEUE,"flashslot.dlx","release.dead",null);
        broker.removeBinding(binding);
        try {
            assertThat(publisher.publish(id)).isTrue();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dead_letter WHERE event_id=?",Integer.class,id)).isEqualTo(1));
            assertThat(inbox.releaseCompleted(id)).isZero();
            assertThat(orders()).isZero();
        } finally { broker.declareBinding(binding); }
    }

    @Test void realDeadLetterRecoveryIsAuditedAtomicAndAsynchronouslyReplayed() throws Exception {
        cancel(true); dispatch.scan(); String id=eventId(); var event=event(id);
        doThrow(new IllegalStateException("temporary consumer unavailable")).when(consumer).consume(event);
        assertThat(publisher.publish(id)).isTrue();
        await().atMost(Duration.ofSeconds(18)).untilAsserted(() -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dead_letter WHERE event_id=?",Integer.class,id)).isEqualTo(1));
        long letter=jdbc.queryForObject("SELECT id FROM dead_letter WHERE event_id=?",Long.class,id);
        assertThat(jdbc.queryForObject("SELECT event_type FROM dead_letter WHERE id=?",String.class,letter)).isEqualTo("RELEASE_DISPATCHED");
        doThrow(new IllegalStateException("audit down")).when(audit).insert(any(com.flashslot.operations.entity.AdminAudit.class));
        replay(letter,"failed-audit",500);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?",String.class,id)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT status FROM dead_letter WHERE id=?",String.class,letter)).isEqualTo("FAILED");
        reset(audit);
        replay(letter,"recover",200); replay(letter,"recover",200);
        assertThat(state()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?",String.class,id)).isEqualTo("NEW");
        // 同一原消息恢复后再次失败，必须重新回到 FAILED，仍能使用新的恢复请求处理。
        assertThat(publisher.publish(id)).isTrue();
        await().atMost(Duration.ofSeconds(18)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT deliveries FROM dead_letter WHERE id=?",Integer.class,letter)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT status FROM dead_letter WHERE id=?",String.class,letter)).isEqualTo("FAILED");
        });
        reset(consumer);
        when(clock.instant()).thenReturn(now.plusSeconds(1800));
        replay(letter,"recover-again",200);
        assertThat(publisher.publish(id)).isTrue(); received(id);
    }
}
