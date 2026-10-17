package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.messaging.entity.OutboxEvent;
import com.flashslot.messaging.mapper.OutboxEventMapper;
import com.flashslot.support.TestUser;
import com.flashslot.waitlist.service.ReleaseJobService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 真实数据库验证自动转正、到期清理、多执行器竞争和故障恢复。 */
@EnabledIfEnvironmentVariable(named = "FLASHSLOT_PRIVATE_TEST", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
class ReleaseJobIntegrationTest {
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean Clock clock;
    @MockitoSpyBean OutboxEventMapper events;
    final Instant now = Instant.parse("2026-10-15T00:00:00Z");
    String owner, first, second, orderNo;
    long ownerId, firstId, secondId, resource, jobId;

    @BeforeEach
    void fixtures() throws Exception {
        when(clock.instant()).thenReturn(now);
        owner = "release_" + UUID.randomUUID();
        first = "release_" + UUID.randomUUID();
        second = "release_" + UUID.randomUUID();
        ownerId = account(owner);
        firstId = account(first);
        secondId = account(second);
        resource = device();
        orderNo = createBooking(owner, resource, 18, 22);
    }

    private ReleaseJobService processor() {
        var beans = context.getBeansOfType(ReleaseJobService.class);
        assertThat(beans).as("自动撮合业务已注册").hasSize(1);
        return beans.values().iterator().next();
    }

    private long account(String name) {
        jdbc.update("INSERT INTO app_user(username,password_hash) VALUES (?,'fixture')", name);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, name);
    }

    private long device() {
        String name = "release-device_" + UUID.randomUUID();
        jdbc.update("INSERT INTO resource(name,location) VALUES (?,'A201')", name);
        long id = jdbc.queryForObject("SELECT id FROM resource WHERE name=?", Long.class, name);
        jdbc.update("INSERT INTO resource_day(resource_id,book_date) VALUES (?,'2026-10-16')", id);
        return id;
    }

    private String interval(String path, String who, long device, int start, int end)
            throws Exception {
        var response =
                mvc.perform(
                                post(path)
                                        .with(new TestUser(jdbc, who))
                                        .with(csrf())
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType("application/json")
                                        .content(
                                                "{\"resourceId\":"
                                                        + device
                                                        + ",\"bookDate\":\"2026-10-16\",\"startSlot\":"
                                                        + start
                                                        + ",\"endSlot\":"
                                                        + end
                                                        + ",\"purpose\":\"STUDY\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        var data = json.readTree(response.getContentAsString()).get("data");
        return data.get(path.endsWith("waitlists") ? "waitlistNo" : "orderNo").asText();
    }

    private String createBooking(String who, long device, int start, int end) throws Exception {
        return interval("/api/v1/bookings", who, device, start, end);
    }

    private String waitlist(String who, int start, int end) throws Exception {
        return interval("/api/v1/waitlists", who, resource, start, end);
    }

    private void cancel() throws Exception {
        mvc.perform(
                        post("/api/v1/bookings/" + orderNo + "/cancel")
                                .with(new TestUser(jdbc, owner))
                                .with(csrf())
                                .header("Idempotency-Key", "cancel")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isOk());
        jobId =
                jdbc.queryForObject(
                        "SELECT id FROM release_job WHERE resource_id=?", Long.class, resource);
    }

    private String jobStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM release_job WHERE id=?", String.class, jobId);
    }

    private String waitlistStatus(String number) {
        return jdbc.queryForObject(
                "SELECT status FROM waitlist_record WHERE waitlist_no=?", String.class, number);
    }

    private int count(String table, String column, Object value) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Integer.class, value);
    }

    @Test
    void cancellationAutomaticallyPromotesFirstEligibleCandidateAndIsIdempotent() throws Exception {
        String a = waitlist(first, 18, 22), b = waitlist(second, 18, 22);
        cancel();
        processor().scanDueJobs();
        assertThat(jobStatus()).isEqualTo("MATCHED");
        assertThat(waitlistStatus(a)).isEqualTo("MATCHED");
        assertThat(waitlistStatus(b)).isEqualTo("WAITING");
        assertThat(count("booking_order", "release_job_id", jobId)).isEqualTo(1);
        assertThat(count("user_slot_occupation", "user_id", firstId)).isEqualTo(4);
        assertThat(count("user_slot_occupation", "user_id", ownerId)).isZero();
        long booked =
                jdbc.queryForObject(
                        "SELECT id FROM booking_order WHERE release_job_id=?", Long.class, jobId);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM slot_occupation WHERE resource_id=? AND"
                                    + " state='BOOKED' AND booking_id=? AND release_job_id IS NULL",
                                Integer.class,
                                resource,
                                booked))
                .isEqualTo(4);
        processor().process(jobId);
        processor().scanDueJobs();
        assertThat(count("booking_order", "release_job_id", jobId)).isEqualTo(1);
    }

    @Test
    void noCandidateRestoresDirectBookingAndDoesNotStealPartialInterval() throws Exception {
        String partial = waitlist(first, 18, 20);
        cancel();
        processor().process(jobId);
        assertThat(jobStatus()).isEqualTo("RELEASED");
        assertThat(waitlistStatus(partial)).isEqualTo("WAITING");
        assertThat(count("slot_occupation", "resource_id", resource)).isZero();
        createBooking(second, resource, 18, 22);
    }

    @Test
    void disabledFirstCandidateIsRejectedAndSecondGetsOrder() throws Exception {
        String a = waitlist(first, 18, 22), b = waitlist(second, 18, 22);
        cancel();
        jdbc.update("UPDATE app_user SET enabled=0 WHERE id=?", firstId);
        processor().process(jobId);
        assertThat(waitlistStatus(a)).isEqualTo("REJECTED");
        assertThat(waitlistStatus(b)).isEqualTo("MATCHED");
        assertThat(count("user_slot_occupation", "user_id", secondId)).isEqualTo(4);
    }

    @Test
    void newUserConflictIsRecheckedAtPromotionTime() throws Exception {
        String a = waitlist(first, 18, 22), b = waitlist(second, 18, 22);
        cancel();
        createBooking(first, device(), 18, 22);
        processor().process(jobId);
        assertThat(waitlistStatus(a)).isEqualTo("REJECTED");
        assertThat(waitlistStatus(b)).isEqualTo("MATCHED");
    }

    @Test
    void exactPromotionDeadlineExpiresCandidateInsteadOfBooking() throws Exception {
        String a = waitlist(first, 18, 22);
        cancel();
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T00:50:00Z"));
        processor().process(jobId);
        assertThat(jobStatus()).isEqualTo("RELEASED");
        assertThat(waitlistStatus(a)).isEqualTo("EXPIRED");
        assertThat(count("booking_order", "release_job_id", jobId)).isZero();
        assertThat(count("slot_occupation", "resource_id", resource)).isZero();
    }

    @Test
    void overdueScanDropsStartedPrefixAndOnlyMatchesExactRemainingSuffix() throws Exception {
        String original = waitlist(first, 18, 22);
        String suffix = waitlist(second, 19, 22);
        cancel();
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T01:05:00Z"));
        processor().process(jobId);
        assertThat(waitlistStatus(original)).isEqualTo("EXPIRED");
        assertThat(waitlistStatus(suffix)).isEqualTo("MATCHED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT start_slot FROM booking_order WHERE release_job_id=?",
                                Integer.class,
                                jobId))
                .isEqualTo(19);
        assertThat(count("slot_occupation", "resource_id", resource)).isEqualTo(3);
        assertThat(count("user_slot_occupation", "user_id", secondId)).isEqualTo(3);
    }

    @Test
    void pausedDeviceRetainsCandidateAndResumesOnlyAfterActivation() throws Exception {
        String a = waitlist(first, 18, 22);
        cancel();
        jdbc.update("UPDATE resource SET status='PAUSED' WHERE id=?", resource);
        processor().process(jobId);
        assertThat(waitlistStatus(a)).isEqualTo("WAITING");
        assertThat(jobStatus()).isEqualTo("PENDING");
        jdbc.update("UPDATE resource SET status='ACTIVE' WHERE id=?", resource);
        processor().process(jobId);
        assertThat(waitlistStatus(a)).isEqualTo("MATCHED");
    }

    @Test
    void closedCalendarRejectsQueueAndReleasesTask() throws Exception {
        String a = waitlist(first, 18, 22);
        cancel();
        jdbc.update("UPDATE resource_day SET enabled=0 WHERE resource_id=?", resource);
        processor().process(jobId);
        assertThat(waitlistStatus(a)).isEqualTo("REJECTED");
        assertThat(jobStatus()).isEqualTo("RELEASED");
        assertThat(count("slot_occupation", "resource_id", resource)).isZero();
    }

    @Test
    void twoWorkersCannotCreateTwoOrders() throws Exception {
        String a = waitlist(first, 18, 22);
        cancel();
        var service = processor();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var x =
                    pool.submit(
                            () -> {
                                start.await();
                                service.process(jobId);
                                return true;
                            });
            var y =
                    pool.submit(
                            () -> {
                                start.await();
                                service.process(jobId);
                                return true;
                            });
            start.countDown();
            x.get(15, TimeUnit.SECONDS);
            y.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(waitlistStatus(a)).isEqualTo("MATCHED");
        assertThat(count("booking_order", "release_job_id", jobId)).isEqualTo(1);
    }

    @Test
    void eventFailureRollsBackPromotionAndPersistsRetryForNextHalfHour() throws Exception {
        String a = waitlist(first, 18, 22);
        cancel();
        doThrow(new IllegalStateException("private injected error"))
                .when(events)
                .insert(any(OutboxEvent.class));
        processor().process(jobId);
        assertThat(waitlistStatus(a)).isEqualTo("WAITING");
        assertThat(jobStatus()).isEqualTo("PENDING");
        assertThat(count("booking_order", "release_job_id", jobId)).isZero();
        assertThat(count("user_slot_occupation", "user_id", firstId)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempts FROM release_job WHERE id=?",
                                Integer.class,
                                jobId))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT next_run_at FROM release_job WHERE id=?",
                                        java.sql.Timestamp.class,
                                        jobId)
                                .toInstant())
                .isEqualTo(now.plusSeconds(1800));
        reset(events);
        when(clock.instant()).thenReturn(now.plusSeconds(1800));
        processor().scanDueJobs();
        assertThat(waitlistStatus(a)).isEqualTo("MATCHED");
        assertThat(jobStatus()).isEqualTo("MATCHED");
    }

    @Test
    void brokenOccupationOwnershipRequiresManualReviewWithoutOverwritingOtherRows()
            throws Exception {
        waitlist(first, 18, 22);
        cancel();
        jdbc.update("DELETE FROM slot_occupation WHERE resource_id=? AND slot_index=21", resource);
        processor().process(jobId);
        assertThat(jobStatus()).isEqualTo("MANUAL_REVIEW");
        assertThat(count("booking_order", "release_job_id", jobId)).isZero();
        assertThat(count("slot_occupation", "resource_id", resource)).isEqualTo(3);
    }

    @Test
    void scanOnlyProcessesDuePendingTasks() throws Exception {
        waitlist(first, 18, 22);
        cancel();
        jdbc.update(
                "UPDATE release_job SET next_run_at=? WHERE id=?",
                java.sql.Timestamp.from(now.plusSeconds(1800)),
                jobId);
        processor().scanDueJobs();
        assertThat(jobStatus()).isEqualTo("PENDING");
        when(clock.instant()).thenReturn(now.plusSeconds(1800));
        processor().scanDueJobs();
        assertThat(jobStatus()).isEqualTo("MATCHED");
    }

    @Test
    void suffixMustBeReselectedWhenClockAdvancesEvenWithoutAnOriginalCandidate() throws Exception {
        String suffix = waitlist(second, 19, 22);
        cancel();
        when(clock.instant())
                .thenReturn(
                        Instant.parse("2026-10-16T00:59:59.999Z"),
                        Instant.parse("2026-10-16T01:00:00.001Z"));
        processor().process(jobId);
        assertThat(waitlistStatus(suffix)).isEqualTo("MATCHED");
        assertThat(jobStatus()).isEqualTo("MATCHED");
    }

    @Test
    void candidateBudgetDoesNotReleaseInventoryBeforeRemainingQueueIsChecked() throws Exception {
        for (int i = 0; i < 21; i++) {
            long candidate = account("budget_" + UUID.randomUUID());
            if (i < 20) jdbc.update("UPDATE app_user SET enabled=0 WHERE id=?", candidate);
            jdbc.update(
                    """
INSERT INTO waitlist_record(waitlist_no,user_id,resource_id,book_date,start_slot,end_slot,purpose,expire_at)
VALUES (?,?,?,'2026-10-16',18,22,'STUDY','2026-10-16 00:50:00')
""",
                    "WL_" + UUID.randomUUID(),
                    candidate,
                    resource);
        }
        cancel();
        processor().process(jobId);
        assertThat(jobStatus()).isEqualTo("PENDING");
        assertThat(count("slot_occupation", "release_job_id", jobId)).isEqualTo(4);
        assertThat(count("booking_order", "release_job_id", jobId)).isZero();
        when(clock.instant()).thenReturn(now.plusSeconds(1800));
        processor().scanDueJobs();
        assertThat(jobStatus()).isEqualTo("MATCHED");
        assertThat(count("booking_order", "release_job_id", jobId)).isEqualTo(1);
    }

    @Test
    void repeatedInfrastructureFailureStopsAfterFiveAttemptsForManualReview() throws Exception {
        waitlist(first, 18, 22);
        cancel();
        doThrow(new IllegalStateException("injected repeated failure"))
                .when(events)
                .insert(any(OutboxEvent.class));
        for (int attempt = 0; attempt < 5; attempt++) {
            when(clock.instant()).thenReturn(now.plusSeconds(attempt * 1800L));
            processor().process(jobId);
        }
        assertThat(jobStatus()).isEqualTo("MANUAL_REVIEW");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempts FROM release_job WHERE id=?",
                                Integer.class,
                                jobId))
                .isEqualTo(5);
        assertThat(count("slot_occupation", "release_job_id", jobId)).isEqualTo(4);
        assertThat(count("booking_order", "release_job_id", jobId)).isZero();
    }
}
