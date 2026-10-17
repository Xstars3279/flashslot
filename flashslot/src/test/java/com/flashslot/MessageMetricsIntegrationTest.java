package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashslot.messaging.mapper.MessageMetricsMapper;
import com.flashslot.messaging.model.PublishedEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Real MySQL aggregate query coverage; fixtures roll back and never enter the publisher. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="FLASHSLOT_PRIVATE_TEST", matches="true")
@Transactional
class MessageMetricsIntegrationTest {
    @Autowired MessageMetricsMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test void databaseSnapshotCountsSupportedBacklogAndSeparatesHistoricalAndFailedEvents() {
        var before = mapper.snapshot(PublishedEvent.names());
        var oldest = Instant.parse("1990-01-01T00:00:00Z");
        insert("RELEASE_DISPATCHED", "NEW", oldest);
        insert("BOOKING_CREATED", "SENDING", oldest.plusSeconds(1));
        insert("LEGACY_TEST", "NEW", oldest.minusSeconds(10));
        insert("LEGACY_TEST", "ARCHIVED", oldest.minusSeconds(20));
        insert("BOOKING_CREATED", "FAILED", oldest);
        insert("BOOKING_CREATED", "PUBLISHED", oldest.minusSeconds(30));
        jdbc.update("""
            INSERT INTO dead_letter(message_hash,body,event_type,error_code,status,received_at)
            VALUES (?,?,'BOOKING_CREATED','TEST','FAILED',?)
            """, UUID.randomUUID().toString().replace("-", "").repeat(2), new byte[] {1}, Timestamp.from(oldest));
        insertPendingRelease(oldest);
        var after = mapper.snapshot(PublishedEvent.names());
        assertThat(after.getOutboxNew()).isEqualTo(before.getOutboxNew() + 1);
        assertThat(after.getOutboxSending()).isEqualTo(before.getOutboxSending() + 1);
        assertThat(after.getOutboxFailed()).isEqualTo(before.getOutboxFailed() + 1);
        assertThat(after.getOutboxArchived()).isEqualTo(before.getOutboxArchived() + 1);
        assertThat(after.getDeadLetterFailed()).isEqualTo(before.getDeadLetterFailed() + 1);
        assertThat(after.getOldestPending()).isEqualTo(oldest);
        assertThat(after.getReleasePending()).isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM release_job WHERE status='PENDING'", Long.class));
        assertThat(after.getReleaseManualReview()).isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM release_job WHERE status='MANUAL_REVIEW'", Long.class));
        assertThat(after.getOldestReleaseDue()).isEqualTo(oldest);
    }

    private void insertPendingRelease(Instant due) {
        String fixture = "metrics-" + UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash) VALUES (?,'fixture')", fixture);
        long user = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, fixture);
        jdbc.update("INSERT INTO resource(name,location) VALUES (?,'metrics')", fixture);
        long resource = jdbc.queryForObject("SELECT id FROM resource WHERE name=?", Long.class, fixture);
        jdbc.update("INSERT INTO resource_day(resource_id,book_date) VALUES (?,'2099-01-01')", resource);
        jdbc.update("""
            INSERT INTO booking_order(order_no,user_id,resource_id,book_date,start_slot,end_slot,charged_slots,
              purpose,start_at,check_in_open_at,check_in_deadline,end_at)
            VALUES (?,?,?,'2099-01-01',0,2,2,'STUDY','2099-01-01 00:00:00','2099-01-01 00:00:00',
              '2099-01-01 00:15:00','2099-01-01 01:00:00')
            """, fixture, user, resource);
        long booking = jdbc.queryForObject("SELECT id FROM booking_order WHERE order_no=?", Long.class, fixture);
        jdbc.update("""
            INSERT INTO release_job(job_no,source_booking_id,resource_id,book_date,start_slot,end_slot,
              effective_start_slot,status,next_run_at)
            VALUES (?,?,?,'2099-01-01',0,2,0,'PENDING',?)
            """, fixture, booking, resource, Timestamp.from(due));
    }

    private void insert(String type, String status, Instant created) {
        jdbc.update("""
            INSERT INTO outbox_event(event_id,event_type,aggregate_id,payload,status,next_run_at,created_at)
            VALUES (?,?,'metrics-fixture','{}',?,?,?)
            """, UUID.randomUUID().toString(), type, status,
                Timestamp.from(Instant.parse("2099-01-01T00:00:00Z")), Timestamp.from(created));
    }
}
