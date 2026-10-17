package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import com.flashslot.messaging.service.BusinessEventService;
import com.flashslot.messaging.service.ScheduleEventService;
import com.flashslot.common.service.HistoryCleanupService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.when;

@SpringBootTest
@EnabledIfEnvironmentVariable(named="FLASHSLOT_PRIVATE_TEST",matches="true")
class MessageGovernanceIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired BusinessEventService events;
    @Autowired ScheduleEventService schedules;
    @Autowired HistoryCleanupService cleanup;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean Clock clock;
    final Instant now=Instant.parse("2026-10-17T00:00:00Z");
    @Test void historyEventsAreArchivedImmediatelyWhileDeliverableEventsRemainNew() {
        when(clock.instant()).thenReturn(now);
        String aggregate="history_"+UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            events.record("CREDIT_CHANGED",aggregate,1,Map.of("scoreAfter",90),now);
            events.record("RELEASE_COMPLETED",aggregate,1,Map.of("status","RELEASED"),now);
            events.record("WAITLIST_CREATED",aggregate,1,Map.of("waitlistNo","WL_history"),now);
            events.record("BOOKING_CREATED",aggregate,1,Map.of("orderNo",aggregate),now);
            schedules.record(900000001L,LocalDate.of(2026,10,18),1,now);
        });
        assertThat(jdbc.queryForList("SELECT status FROM outbox_event WHERE aggregate_id=? AND event_type<>'BOOKING_CREATED'",String.class,aggregate)).containsOnly("ARCHIVED");
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE aggregate_id=? AND event_type='BOOKING_CREATED'",String.class,aggregate)).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_type='SCHEDULE_CHANGED' AND aggregate_id='900000001:2026-10-18'",String.class)).isEqualTo("ARCHIVED");
    }
    @Test void historyRetentionDoesNotDeleteUnsentFailedOrPublishedEvents() {
        when(clock.instant()).thenReturn(now);
        String aggregate="retention_"+UUID.randomUUID();
        for(String state:List.of("ARCHIVED","NEW","SENDING","PUBLISHED","FAILED")) {
            jdbc.update("""
                INSERT INTO outbox_event(event_id,event_type,aggregate_id,payload,status,next_run_at,created_at)
                VALUES (?, 'SCHEDULE_CHANGED', ?,JSON_OBJECT('schemaVersion',1),?,'2020-01-01','2020-01-01')
                """,UUID.randomUUID().toString(),aggregate,state);
        }
        jdbc.update("""
            INSERT INTO outbox_event(event_id,event_type,aggregate_id,payload,status,next_run_at,created_at)
            VALUES (?, 'CREDIT_CHANGED', ?,JSON_OBJECT('schemaVersion',1),'ARCHIVED',?,?)
            """,UUID.randomUUID().toString(),aggregate,java.sql.Timestamp.from(now),java.sql.Timestamp.from(now));
        cleanup.cleanupBatch();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",Integer.class,aggregate)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND status='ARCHIVED'",Integer.class,aggregate)).isEqualTo(1);
    }
}
