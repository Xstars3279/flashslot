package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.operations.mapper.AdminAuditMapper;
import com.flashslot.support.TestUser;
import com.flashslot.waitlist.service.WaitlistMatchingService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.*;
import java.util.UUID;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "FLASHSLOT_PRIVATE_TEST", matches = "true")
class RecoveryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired WaitlistMatchingService matching;
    @MockitoSpyBean AdminAuditMapper audit;
    @MockitoBean Clock clock;
    long owner, other, admin, resource;
    String candidate;
    Instant now = Instant.parse("2026-10-15T00:00:00Z");

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(now);
        owner = account("USER");
        other = account("USER");
        admin = account("ADMIN");
        String name = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO resource(name,location) VALUES (?,'test')", name);
        resource = jdbc.queryForObject("SELECT id FROM resource WHERE name=?", Long.class, name);
        jdbc.update(
                "INSERT INTO resource_day(resource_id,book_date) VALUES (?,'2026-10-16')",
                resource);
        candidate = "WL_" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " waitlist_record(waitlist_no,user_id,resource_id,book_date,start_slot,end_slot,purpose,expire_at)"
                    + " VALUES (?,?,?,'2026-10-16',18,20,'STUDY','2026-10-16 00:50:00')",
                candidate,
                owner,
                resource);
    }

    long account(String role) {
        String name = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,role) VALUES (?,'fixture',?)",
                name,
                role);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, name);
    }

    TestUser user(long id) {
        String name =
                jdbc.queryForObject("SELECT username FROM app_user WHERE id=?", String.class, id);
        return new TestUser(jdbc, name).roles(id == admin ? "ADMIN" : "USER");
    }

    MockHttpServletRequestBuilder action(String path, long id, String key, String body) {
        return post("/api/v1/" + path)
                .with(user(id))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body);
    }

    @Test
    void cancelIsPrivateAndIdempotent() throws Exception {
        String path = "me/waitlists/" + candidate + "/cancel";
        mvc.perform(action(path, other, "foreign", "{}")).andExpect(status().isNotFound());
        mvc.perform(action(path, owner, "cancel", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.status").value("CANCELED"));
        mvc.perform(action(path, owner, "cancel", "{}")).andExpect(status().isOk());
        mvc.perform(action(path, owner, "again", "{}")).andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND"
                                    + " event_type='WAITLIST_CANCELED'",
                                Integer.class,
                                candidate))
                .isEqualTo(1);
    }

    @Test
    void expiredCandidateIsNotReportedAsCanceled() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T00:50:00Z"));
        mvc.perform(action("me/waitlists/" + candidate + "/cancel", owner, "expired", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.status").value("EXPIRED"));
    }

    @Test
    void globalExpiryWorksWithoutReleaseJobAndRunsOnce() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T00:50:00Z"));
        Object service = context.getBean("waitlistExpiryServiceImpl");
        service.getClass().getMethod("scanExpired").invoke(service);
        service.getClass().getMethod("scanExpired").invoke(service);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM waitlist_record WHERE waitlist_no=?",
                                String.class,
                                candidate))
                .isEqualTo("EXPIRED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND"
                                    + " event_type='WAITLIST_EXPIRED'",
                                Integer.class,
                                candidate))
                .isEqualTo(1);
    }

    @Test
    void creditAdjustmentRequiresAdminAndReplaysExactlyOnce() throws Exception {
        String path = "admin/users/" + owner + "/credit-adjustments";
        mvc.perform(action(path, owner, "bad", "{\"delta\":-20,\"reason\":\"复核\"}"))
                .andExpect(status().isForbidden());
        String body = "{\"delta\":-20,\"reason\":\"复核\"}";
        mvc.perform(action(path, admin, "adjust", body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.creditScore").value(80));
        mvc.perform(action(path, admin, "adjust", body)).andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT credit_score FROM app_user WHERE id=?",
                                Integer.class,
                                owner))
                .isEqualTo(80);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM credit_ledger WHERE user_id=?",
                                Integer.class,
                                owner))
                .isEqualTo(1);
        mvc.perform(action(path, admin, "adjust", "{\"delta\":-10,\"reason\":\"复核\"}"))
                .andExpect(status().isConflict());
        mvc.perform(action(path, admin, "overflow", "{\"delta\":30,\"reason\":\"复核\"}"))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE app_user SET role='USER' WHERE id=?", admin);
        mvc.perform(action(path, admin, "adjust", body)).andExpect(status().isForbidden());
    }

    @Test
    void restrictionClearRequiresReasonAndDoesNotChangeScore() throws Exception {
        jdbc.update(
                "UPDATE app_user SET credit_score=50,restriction_until='2026-10-22' WHERE id=?",
                owner);
        String path = "admin/users/" + owner + "/restriction-clear";
        mvc.perform(action(path, admin, "blank", "{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(action(path, admin, "clear", "{\"reason\":\"申诉通过\"}"))
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT restriction_until FROM app_user WHERE id=?",
                                Object.class,
                                owner))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT credit_score FROM app_user WHERE id=?",
                                Integer.class,
                                owner))
                .isEqualTo(50);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM admin_audit WHERE"
                                    + " operation='RESTRICTION_CLEAR' AND target_id=?",
                                Integer.class,
                                Long.toString(owner)))
                .isEqualTo(1);
    }

    @Test
    void failedEventCanBeRequeuedButPublishedEventCannot() throws Exception {
        String event = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO"
                    + " outbox_event(event_id,event_type,aggregate_id,payload,status,attempts,next_run_at)"
                    + " VALUES"
                    + " (?,'WAITLIST_EXPIRED',?,JSON_OBJECT('schemaVersion',1,'userId',?,'data',JSON_OBJECT('waitlistNo',?)),'FAILED',12,UTC_TIMESTAMP(3))",
                event,
                candidate,
                owner,
                candidate);
        String path = "admin/messages/" + event + "/retry";
        mvc.perform(action(path, admin, "retry", "{\"reason\":\"MQ已恢复\"}"))
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM outbox_event WHERE event_id=?",
                                String.class,
                                event))
                .isEqualTo("NEW");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempts FROM outbox_event WHERE event_id=?",
                                Integer.class,
                                event))
                .isZero();
        jdbc.update("UPDATE outbox_event SET status='PUBLISHED' WHERE event_id=?", event);
        mvc.perform(action(path, admin, "again", "{\"reason\":\"重投\"}"))
                .andExpect(status().isConflict());
    }

    String releaseJob() throws Exception {
        var response =
                mvc.perform(
                                action(
                                        "bookings",
                                        other,
                                        UUID.randomUUID().toString(),
                                        "{\"resourceId\":"
                                                + resource
                                                + ",\"bookDate\":\"2026-10-16\",\"startSlot\":18,\"endSlot\":20,\"purpose\":\"STUDY\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String order =
                json.readTree(response.getResponse().getContentAsString())
                        .path("data")
                        .path("orderNo")
                        .asText();
        mvc.perform(
                        action(
                                "bookings/" + order + "/cancel",
                                other,
                                UUID.randomUUID().toString(),
                                "{}"))
                .andExpect(status().isOk());
        String job =
                jdbc.queryForObject(
                        "SELECT job_no FROM release_job WHERE resource_id=?",
                        String.class,
                        resource);
        jdbc.update("UPDATE release_job SET status='MANUAL_REVIEW',attempts=5 WHERE job_no=?", job);
        return job;
    }

    @Test
    void releaseRetryIsAuditedAndMatchingStillUsesNormalWorker() throws Exception {
        String job = releaseJob();
        String path = "admin/release-jobs/" + job + "/retry";
        mvc.perform(action(path, admin, "retry-job", "{\"reason\":\"核查完成\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.status").value("PENDING"));
        mvc.perform(action(path, admin, "retry-job", "{\"reason\":\"核查完成\"}"))
                .andExpect(status().isOk());
        long id = jdbc.queryForObject("SELECT id FROM release_job WHERE job_no=?", Long.class, job);
        matching.matchNext(id);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM waitlist_record WHERE waitlist_no=?",
                                String.class,
                                candidate))
                .isEqualTo("MATCHED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM admin_audit WHERE target_id=? AND"
                                    + " operation='RELEASE_RETRY'",
                                Integer.class,
                                job))
                .isEqualTo(1);
    }

    @Test
    void manualReleaseDoesNotTouchForeignOccupationsAndRejectsCorruption() throws Exception {
        String job = releaseJob();
        jdbc.update("DELETE FROM slot_occupation WHERE resource_id=? AND slot_index=18", resource);
        mvc.perform(
                        action(
                                "admin/release-jobs/" + job + "/release",
                                admin,
                                "broken",
                                "{\"reason\":\"人工核查\"}"))
                .andExpect(status().isConflict());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM slot_occupation WHERE resource_id=?",
                                Integer.class,
                                resource))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM release_job WHERE job_no=?", String.class, job))
                .isEqualTo("MANUAL_REVIEW");
    }

    @Test
    void manualReleaseFreesOnlyOwnedSlotsAndBumpsRevisionOnce() throws Exception {
        String job = releaseJob();
        long before =
                jdbc.queryForObject(
                        "SELECT revision FROM resource_day WHERE resource_id=?",
                        Long.class,
                        resource);
        String path = "admin/release-jobs/" + job + "/release";
        mvc.perform(action(path, admin, "release", "{\"reason\":\"管理员放回库存\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.status").value("RELEASED"));
        mvc.perform(action(path, admin, "release", "{\"reason\":\"管理员放回库存\"}"))
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM slot_occupation WHERE resource_id=?",
                                Integer.class,
                                resource))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revision FROM resource_day WHERE resource_id=?",
                                Long.class,
                                resource))
                .isEqualTo(before + 1);
    }

    @Test
    void auditFailureRollsBackScoreAndLedgerAndIdempotency() throws Exception {
        doThrow(new IllegalStateException("audit failure"))
                .when(audit)
                .insert(any(com.flashslot.operations.entity.AdminAudit.class));
        try {
            mvc.perform(
                            action(
                                    "admin/users/" + owner + "/credit-adjustments",
                                    admin,
                                    "rollback",
                                    "{\"delta\":-20,\"reason\":\"复核\"}"))
                    .andExpect(status().isInternalServerError());
        } finally {
            reset(audit);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT credit_score FROM app_user WHERE id=?",
                                Integer.class,
                                owner))
                .isEqualTo(100);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM credit_ledger WHERE user_id=?",
                                Integer.class,
                                owner))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM api_idempotency WHERE user_id=? AND"
                                    + " request_key='rollback'",
                                Integer.class,
                                admin))
                .isZero();
    }

    @Test
    void cancellationAndPromotionCannotBothWin() throws Exception {
        String job = releaseJob();
        jdbc.update("UPDATE release_job SET status='PENDING' WHERE job_no=?", job);
        long id = jdbc.queryForObject("SELECT id FROM release_job WHERE job_no=?", Long.class, job);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            var cancel =
                    pool.submit(
                            () -> {
                                gate.await();
                                return mvc.perform(
                                                action(
                                                        "me/waitlists/" + candidate + "/cancel",
                                                        owner,
                                                        "race",
                                                        "{}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            var match =
                    pool.submit(
                            () -> {
                                gate.await();
                                return matching.matchNext(id);
                            });
            gate.countDown();
            int status = cancel.get(10, java.util.concurrent.TimeUnit.SECONDS);
            match.get(10, java.util.concurrent.TimeUnit.SECONDS);
            String state =
                    jdbc.queryForObject(
                            "SELECT status FROM waitlist_record WHERE waitlist_no=?",
                            String.class,
                            candidate);
            assertThat(state).isIn("CANCELED", "MATCHED");
            assertThat(status).isEqualTo("CANCELED".equals(state) ? 200 : 409);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM booking_order WHERE user_id=? AND"
                                        + " resource_id=?",
                                    Integer.class,
                                    owner,
                                    resource))
                    .isEqualTo("MATCHED".equals(state) ? 1 : 0);
        } finally {
            pool.shutdownNow();
        }
    }
}
