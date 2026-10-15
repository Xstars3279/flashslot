package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.flashslot.messaging.entity.OutboxEvent;
import com.flashslot.messaging.mapper.OutboxEventMapper;
import com.flashslot.support.TestUser;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 三个新增接口的真实 MySQL 事务、状态边界、幂等与归属验证。 */
@EnabledIfEnvironmentVariable(named = "FLASHSLOT_PRIVATE_TEST", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
class CancellationWaitlistIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean Clock clock;
    @MockitoSpyBean OutboxEventMapper events;
    final Instant now = Instant.parse("2026-10-15T00:00:00Z");
    String owner, candidate, admin;
    long ownerId, candidateId, resource;

    @BeforeEach
    void fixtures() {
        when(clock.instant()).thenReturn(now);
        owner = "owner_" + UUID.randomUUID();
        candidate = "wl_" + UUID.randomUUID();
        admin = "admin_" + UUID.randomUUID();
        ownerId = account(owner, "USER");
        candidateId = account(candidate, "USER");
        account(admin, "ADMIN");
        resource = resource();
    }

    private long account(String name, String role) {
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,role) VALUES (?,'fixture',?)",
                name,
                role);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, name);
    }

    private long resource() {
        String name = "cancel_" + UUID.randomUUID();
        jdbc.update("INSERT INTO resource(name,location) VALUES (?,'A201')", name);
        long id = jdbc.queryForObject("SELECT id FROM resource WHERE name=?", Long.class, name);
        jdbc.update("INSERT INTO resource_day(resource_id,book_date) VALUES (?,'2026-10-16')", id);
        return id;
    }

    private TestUser user(String name) {
        return new TestUser(jdbc, name);
    }

    private MockHttpServletRequestBuilder interval(
            String path, String who, String key, long device, int start, int end) {
        return post(path)
                .with(user(who))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(
                        "{\"resourceId\":"
                                + device
                                + ",\"bookDate\":\"2026-10-16\",\"startSlot\":"
                                + start
                                + ",\"endSlot\":"
                                + end
                                + ",\"purpose\":\"EXPERIMENT\"}");
    }

    private String booking(long device, int start, int end) throws Exception {
        var result =
                mvc.perform(
                                interval(
                                        "/api/v1/bookings",
                                        owner,
                                        UUID.randomUUID().toString(),
                                        device,
                                        start,
                                        end))
                        .andExpect(status().isCreated())
                        .andReturn();
        return data(result).get("orderNo").asText();
    }

    private MockHttpServletRequestBuilder cancel(String who, String key, String order) {
        return post("/api/v1/bookings/" + order + "/cancel")
                .with(user(who))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content("{}");
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private int count(String table, String column, Object value) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Integer.class, value);
    }

    @Test
    void cancelMovesFutureSlotsToHandoffAndReplaysWithoutDuplicateRelease() throws Exception {
        String order = booking(resource, 18, 22);
        var first =
                mvc.perform(cancel(owner, "cancel", order))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("data.status").value("CANCELED"))
                        .andExpect(jsonPath("data.chargedSlots").value(0))
                        .andExpect(jsonPath("data.releaseStatus").value("PENDING"))
                        .andReturn();
        long id =
                jdbc.queryForObject(
                        "SELECT id FROM booking_order WHERE order_no=?", Long.class, order);
        assertThat(count("release_job", "source_booking_id", id)).isEqualTo(1);
        assertThat(count("user_slot_occupation", "booking_id", id)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM slot_occupation WHERE resource_id=? AND"
                                    + " state='HANDOFF'",
                                Integer.class,
                                resource))
                .isEqualTo(4);
        var replay =
                mvc.perform(cancel(owner, "cancel", order)).andExpect(status().isOk()).andReturn();
        assertThat(data(replay)).isEqualTo(data(first));
        mvc.perform(cancel(owner, "another-key", order)).andExpect(status().isOk());
        assertThat(count("release_job", "source_booking_id", id)).isEqualTo(1);
        mvc.perform(interval("/api/v1/bookings", candidate, "handoff", resource, 18, 22))
                .andExpect(status().isConflict());
        // 相同用户同一键可以用于不同 operation，不能把取消结果错误回放为候补结果。
        mvc.perform(interval("/api/v1/waitlists", owner, "cancel", resource, 18, 22))
                .andExpect(status().isCreated());
    }

    @Test
    void cancellationCannotTouchAnotherUsersOrderAndAdminMustGiveReason() throws Exception {
        String order = booking(resource, 18, 22);
        mvc.perform(cancel(candidate, "foreign", order)).andExpect(status().isNotFound());
        mvc.perform(cancel(admin, "no-reason", order).with(user(admin).roles("ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        cancel(admin, "admin", order)
                                .with(user(admin).roles("ADMIN"))
                                .content("{\"reason\":\"设备维护\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.terminationReason").value("ADMIN_CANCEL"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM admin_audit WHERE operation='BOOKING_CANCEL'"
                                    + " AND target_id=?",
                                Integer.class,
                                order))
                .isEqualTo(1);
        jdbc.update("UPDATE app_user SET role='USER' WHERE username=?", admin);
        mvc.perform(
                        cancel(admin, "admin", order)
                                .with(user(admin).roles("ADMIN"))
                                .content("{\"reason\":\"设备维护\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("data").isEmpty());
    }

    @Test
    void cancellationAfterStartReleasesOnlyNotYetStartedSlots() throws Exception {
        String order = booking(resource, 18, 22);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T01:00:00.001Z"));
        mvc.perform(cancel(owner, "partial", order))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.chargedSlots").value(1));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM slot_occupation WHERE resource_id=? AND"
                                    + " slot_index=18",
                                String.class,
                                resource))
                .isEqualTo("BOOKED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM slot_occupation WHERE resource_id=? AND"
                                    + " state='HANDOFF'",
                                Integer.class,
                                resource))
                .isEqualTo(3);
    }

    @Test
    void exactDeadlineNormalizesNoShowAndChargesCreditOnlyOnce() throws Exception {
        String order = booking(resource, 18, 22);
        jdbc.update("UPDATE app_user SET credit_score=65 WHERE id=?", ownerId);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T01:15:00Z"));
        mvc.perform(cancel(owner, "deadline", order))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.status").value("NO_SHOW"))
                .andExpect(jsonPath("data.chargedSlots").value(1));
        mvc.perform(cancel(owner, "repeat", order)).andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT credit_score FROM app_user WHERE id=?",
                                Integer.class,
                                ownerId))
                .isEqualTo(55);
        assertThat(count("credit_ledger", "user_id", ownerId)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT restriction_until FROM app_user WHERE id=?",
                                        java.sql.Timestamp.class,
                                        ownerId)
                                .toInstant())
                .isEqualTo(Instant.parse("2026-10-23T01:15:00Z"));
    }

    @Test
    void checkedInOrderAlreadyEndedBecomesCompletedRatherThanCanceled() throws Exception {
        String order = booking(resource, 18, 22);
        jdbc.update(
                "UPDATE booking_order SET status='CHECKED_IN',checked_in_at='2026-10-16 01:00:00'"
                    + " WHERE order_no=?",
                order);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T03:00:00Z"));
        mvc.perform(cancel(owner, "complete", order))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.status").value("COMPLETED"));
        assertThat(count("release_job", "resource_id", resource)).isZero();
        assertThat(count("credit_ledger", "user_id", ownerId)).isZero();
    }

    @Test
    void outboxFailureRollsBackCancellationOccupationsAndTask() throws Exception {
        String order = booking(resource, 18, 22);
        doThrow(new IllegalStateException("injected event failure"))
                .when(events)
                .insert(any(OutboxEvent.class));
        mvc.perform(cancel(owner, "failed", order)).andExpect(status().isInternalServerError());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM booking_order WHERE order_no=?",
                                String.class,
                                order))
                .isEqualTo("CONFIRMED");
        assertThat(count("release_job", "resource_id", resource)).isZero();
        assertThat(count("user_slot_occupation", "user_id", ownerId)).isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM slot_occupation WHERE resource_id=? AND"
                                    + " state='BOOKED'",
                                Integer.class,
                                resource))
                .isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM api_idempotency WHERE user_id=? AND"
                                    + " operation='CANCEL_BOOKING'",
                                Integer.class,
                                ownerId))
                .isZero();
    }

    @Test
    void concurrentCancellationCreatesOneReleaseTask() throws Exception {
        String order = booking(resource, 18, 22);
        var results = concurrently(cancel(owner, "a", order), cancel(owner, "b", order));
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).toList())
                .containsOnly(200);
        assertThat(count("release_job", "resource_id", resource)).isEqualTo(1);
    }

    @Test
    void waitlistRequiresOccupiedRangeAndDoesNotReserveUserSlots() throws Exception {
        mvc.perform(interval("/api/v1/waitlists", candidate, "free", resource, 18, 22))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("区间空闲，请直接预约"))));
        booking(resource, 18, 22);
        var first =
                mvc.perform(interval("/api/v1/waitlists", candidate, "wait", resource, 18, 22))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("data.status").value("WAITING"))
                        .andExpect(jsonPath("data.expireAt").value("2026-10-16T00:50:00Z"))
                        .andReturn();
        assertThat(count("user_slot_occupation", "user_id", candidateId)).isZero();
        var replay =
                mvc.perform(interval("/api/v1/waitlists", candidate, "wait", resource, 18, 22))
                        .andExpect(status().isCreated())
                        .andReturn();
        assertThat(data(replay)).isEqualTo(data(first));
        mvc.perform(interval("/api/v1/waitlists", candidate, "wait", resource, 20, 22))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("此请求键已用于其他参数"))));
        mvc.perform(interval("/api/v1/waitlists", candidate, "duplicate", resource, 18, 22))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of(
                                                        "已有同区间待处理候补", "已有同设备同区间的待处理候补"))));
    }

    @Test
    void waitlistAcceptsHandoffAndRejectsRestrictedOrOverlappingUser() throws Exception {
        String order = booking(resource, 18, 22);
        mvc.perform(interval("/api/v1/waitlists", owner, "own-overlap", resource, 18, 22))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("你在此时段已有其他预约", "你在此时段已有预约"))));
        mvc.perform(cancel(owner, "handoff-cancel", order)).andExpect(status().isOk());
        mvc.perform(interval("/api/v1/waitlists", candidate, "handoff-wait", resource, 18, 22))
                .andExpect(status().isCreated());
        jdbc.update("UPDATE app_user SET restriction_until='2099-01-01' WHERE id=?", candidateId);
        mvc.perform(interval("/api/v1/waitlists", candidate, "restricted", resource, 20, 22))
                .andExpect(status().isForbidden());
    }

    @Test
    void concurrentDuplicateWaitlistsHaveOnlyOneActiveRecord() throws Exception {
        booking(resource, 18, 22);
        var results =
                concurrently(
                        interval("/api/v1/waitlists", candidate, "a", resource, 18, 22),
                        interval("/api/v1/waitlists", candidate, "b", resource, 18, 22));
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).sorted().toList())
                .containsExactly(201, 409);
        assertThat(count("waitlist_record", "user_id", candidateId)).isEqualTo(1);
    }

    @Test
    void dailyThreeWaitingLimitIsSharedAcrossDevices() throws Exception {
        for (int i = 0; i < 3; i++) {
            long device = resource();
            booking(device, 18 + i * 2, 20 + i * 2);
            mvc.perform(
                            interval(
                                    "/api/v1/waitlists",
                                    candidate,
                                    "limit-" + i,
                                    device,
                                    18 + i * 2,
                                    20 + i * 2))
                    .andExpect(status().isCreated());
        }
        booking(resource, 26, 28);
        mvc.perform(interval("/api/v1/waitlists", candidate, "fourth", resource, 26, 28))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("每日最多三个待处理候补"))));
    }

    @Test
    void ownWaitlistPageDoesNotExposeOtherPeopleAndShowsApproximateQueuePosition()
            throws Exception {
        booking(resource, 18, 22);
        var created =
                mvc.perform(interval("/api/v1/waitlists", candidate, "mine", resource, 18, 22))
                        .andExpect(status().isCreated())
                        .andReturn();
        String number = data(created).get("waitlistNo").asText();
        mvc.perform(get("/api/v1/me/waitlists").with(user(candidate)).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items[0].waitlistNo").value(number))
                .andExpect(jsonPath("data.items[0].queuePosition").value(1))
                .andExpect(jsonPath("data.hasMore").value(false));
        mvc.perform(get("/api/v1/me/waitlists").with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items").isEmpty());
        mvc.perform(get("/api/v1/me/waitlists").with(user(candidate)).param("limit", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/waitlists")).andExpect(status().isUnauthorized());
        var second =
                mvc.perform(interval("/api/v1/waitlists", admin, "second", resource, 18, 22))
                        .andExpect(status().isCreated())
                        .andReturn();
        assertThat(data(second).get("queuePosition").asInt()).isEqualTo(2);
        mvc.perform(
                        get("/api/v1/me/waitlists")
                                .with(user(candidate))
                                .param("cursor", data(second).get("waitlistNo").asText()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancellationNeverReleasesSlotsNowOwnedByAnotherOrder() throws Exception {
        String original = booking(resource, 18, 22), another = booking(resource, 24, 26);
        long otherBooking =
                jdbc.queryForObject(
                        "SELECT id FROM booking_order WHERE order_no=?", Long.class, another);
        jdbc.update(
                "UPDATE slot_occupation SET booking_id=? WHERE resource_id=? AND slot_index=21",
                otherBooking,
                resource);
        mvc.perform(cancel(owner, "corrupt", original)).andExpect(status().isInternalServerError());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT booking_id FROM slot_occupation WHERE resource_id=? AND"
                                    + " slot_index=21",
                                Long.class,
                                resource))
                .isEqualTo(otherBooking);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM booking_order WHERE order_no=?",
                                String.class,
                                original))
                .isEqualTo("CONFIRMED");
        assertThat(count("release_job", "resource_id", resource)).isZero();
        assertThat(count("user_slot_occupation", "user_id", ownerId)).isEqualTo(6);
    }

    @Test
    void waitlistWriteRollsBackOnEventFailureAndOriginalKeyCanRetry() throws Exception {
        booking(resource, 18, 22);
        doThrow(new IllegalStateException("injected waitlist event failure"))
                .when(events)
                .insert(any(OutboxEvent.class));
        mvc.perform(interval("/api/v1/waitlists", candidate, "rollback", resource, 18, 22))
                .andExpect(status().isInternalServerError());
        assertThat(count("waitlist_record", "user_id", candidateId)).isZero();
        assertThat(count("api_idempotency", "user_id", candidateId)).isZero();
        reset(events);
        mvc.perform(interval("/api/v1/waitlists", candidate, "rollback", resource, 18, 22))
                .andExpect(status().isCreated());
    }

    @Test
    void waitlistCutoffChecksExactTimeAndIntervalValidation() throws Exception {
        booking(resource, 18, 22);
        mvc.perform(interval("/api/v1/waitlists", candidate, "too-long", resource, 16, 25))
                .andExpect(status().isBadRequest());
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T00:30:00Z"));
        mvc.perform(interval("/api/v1/waitlists", candidate, "cutoff", resource, 18, 22))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("已到申请截止时间", "已到预约截止时间"))));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T00:29:59.999Z"));
        mvc.perform(interval("/api/v1/waitlists", candidate, "before", resource, 18, 22))
                .andExpect(status().isCreated());
    }

    @Test
    void waitlistPaginationUsesOwnCursorAndDoesNotRepeatItems() throws Exception {
        booking(resource, 18, 24);
        var first =
                mvc.perform(interval("/api/v1/waitlists", candidate, "one", resource, 18, 20))
                        .andExpect(status().isCreated())
                        .andReturn();
        var second =
                mvc.perform(interval("/api/v1/waitlists", candidate, "two", resource, 20, 22))
                        .andExpect(status().isCreated())
                        .andReturn();
        var page =
                mvc.perform(get("/api/v1/me/waitlists").with(user(candidate)).param("limit", "1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("data.hasMore").value(true))
                        .andReturn();
        assertThat(data(page).at("/items/0/waitlistNo").asText())
                .isEqualTo(data(second).get("waitlistNo").asText());
        mvc.perform(
                        get("/api/v1/me/waitlists")
                                .with(user(candidate))
                                .param("limit", "1")
                                .param("cursor", data(page).get("nextCursor").asText()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("data.items[0].waitlistNo")
                                .value(data(first).get("waitlistNo").asText()))
                .andExpect(jsonPath("data.hasMore").value(false));
    }

    @Test
    void concurrentDevicesCannotExceedDailyWaitingLimit() throws Exception {
        long[] devices = new long[4];
        for (int i = 0; i < 4; i++) {
            devices[i] = resource();
            booking(devices[i], 18 + 2 * i, 20 + 2 * i);
            if (i < 2)
                mvc.perform(
                                interval(
                                        "/api/v1/waitlists",
                                        candidate,
                                        "initial-" + i,
                                        devices[i],
                                        18 + 2 * i,
                                        20 + 2 * i))
                        .andExpect(status().isCreated());
        }
        var results =
                concurrently(
                        interval("/api/v1/waitlists", candidate, "third-a", devices[2], 22, 24),
                        interval("/api/v1/waitlists", candidate, "third-b", devices[3], 24, 26));
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).sorted().toList())
                .containsExactly(201, 409);
        assertThat(count("waitlist_record", "user_id", candidateId)).isEqualTo(3);
    }

    @Test
    void noShowFailureAlsoRollsBackCreditAndRestriction() throws Exception {
        String order = booking(resource, 18, 22);
        jdbc.update("UPDATE app_user SET credit_score=65 WHERE id=?", ownerId);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T01:15:00Z"));
        doThrow(new IllegalStateException("injected no-show event failure"))
                .when(events)
                .insert(any(OutboxEvent.class));
        mvc.perform(cancel(owner, "no-show-failure", order))
                .andExpect(status().isInternalServerError());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT credit_score FROM app_user WHERE id=?",
                                Integer.class,
                                ownerId))
                .isEqualTo(65);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT restriction_until FROM app_user WHERE id=?",
                                java.sql.Timestamp.class,
                                ownerId))
                .isNull();
        assertThat(count("credit_ledger", "user_id", ownerId)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM booking_order WHERE order_no=?",
                                String.class,
                                order))
                .isEqualTo("CONFIRMED");
        assertThat(count("release_job", "resource_id", resource)).isZero();
    }

    @Test
    void checkedInEarlyFinishAtSlotBoundaryReleasesThatSlotAndKeepsCheckin() throws Exception {
        String order = booking(resource, 18, 22);
        jdbc.update(
                "UPDATE booking_order SET status='CHECKED_IN',checked_in_at='2026-10-16 01:00:00'"
                    + " WHERE order_no=?",
                order);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T01:30:00Z"));
        mvc.perform(cancel(owner, "early", order))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.terminationReason").value("EARLY_FINISH"))
                .andExpect(jsonPath("data.chargedSlots").value(1))
                .andExpect(jsonPath("data.checkedInAt").value("2026-10-16T01:00:00Z"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM slot_occupation WHERE resource_id=? AND"
                                    + " slot_index=19",
                                String.class,
                                resource))
                .isEqualTo("HANDOFF");
    }

    @Test
    void noShowCreditNeverDropsBelowZeroOrShortensExistingRestriction() throws Exception {
        String order = booking(resource, 18, 22);
        jdbc.update(
                "UPDATE app_user SET credit_score=5,restriction_until='2026-11-01 00:00:00' WHERE"
                    + " id=?",
                ownerId);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T01:15:00Z"));
        mvc.perform(cancel(owner, "low-credit", order))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.status").value("NO_SHOW"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT credit_score FROM app_user WHERE id=?",
                                Integer.class,
                                ownerId))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT delta FROM credit_ledger WHERE user_id=?",
                                Integer.class,
                                ownerId))
                .isEqualTo(-5);
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT restriction_until FROM app_user WHERE id=?",
                                        java.sql.Timestamp.class,
                                        ownerId)
                                .toInstant())
                .isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
    }

    private List<MvcResult> concurrently(
            MockHttpServletRequestBuilder first, MockHttpServletRequestBuilder second)
            throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a =
                    pool.submit(
                            () -> {
                                start.await();
                                return mvc.perform(first).andReturn();
                            });
            var b =
                    pool.submit(
                            () -> {
                                start.await();
                                return mvc.perform(second).andReturn();
                            });
            start.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
