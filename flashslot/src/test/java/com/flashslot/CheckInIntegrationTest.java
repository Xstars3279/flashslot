package com.flashslot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.flashslot.booking.service.BookingExpiryService;
import com.flashslot.messaging.entity.OutboxEvent;
import com.flashslot.messaging.mapper.OutboxEventMapper;
import com.flashslot.messaging.service.ScheduleEventService;
import com.flashslot.support.TestUser;
import com.flashslot.waitlist.service.ReleaseJobService;

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

/** 真实数据库验证签到、超时竞争与原子释放，不依赖真实时间等待。 */
@EnabledIfEnvironmentVariable(named = "FLASHSLOT_PRIVATE_TEST", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
class CheckInIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired BookingExpiryService expiry;
    @Autowired ReleaseJobService releases;
    @MockitoSpyBean ScheduleEventService scheduleEvents;
    @MockitoBean Clock clock;
    @MockitoSpyBean OutboxEventMapper events;
    String owner, other, admin, order;
    long ownerId, resource;
    final Instant opening = Instant.parse("2026-10-16T00:50:00Z");
    final Instant deadline = Instant.parse("2026-10-16T01:15:00Z");

    @BeforeEach
    void fixtures() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-15T00:00:00Z"));
        owner = "check_" + UUID.randomUUID();
        other = "other_" + UUID.randomUUID();
        admin = "admin_" + UUID.randomUUID();
        ownerId = account(owner, "USER");
        account(other, "USER");
        account(admin, "ADMIN");
        String name = "check-device_" + UUID.randomUUID();
        jdbc.update("INSERT INTO resource(name,location) VALUES (?,'A201')", name);
        resource = jdbc.queryForObject("SELECT id FROM resource WHERE name=?", Long.class, name);
        jdbc.update(
                "INSERT INTO resource_day(resource_id,book_date) VALUES (?,'2026-10-16')",
                resource);
        var result =
                mvc.perform(
                                post("/api/v1/bookings")
                                        .with(user(owner))
                                        .with(csrf())
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType("application/json")
                                        .content(
                                                "{\"resourceId\":"
                                                        + resource
                                                        + ",\"bookDate\":\"2026-10-16\",\"startSlot\":18,\"endSlot\":22,\"purpose\":\"STUDY\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        order = data(result).get("orderNo").asText();
        when(clock.instant()).thenReturn(opening);
    }

    private long account(String name, String role) {
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,role) VALUES (?,'fixture',?)",
                name,
                role);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, name);
    }

    private TestUser user(String name) {
        return new TestUser(jdbc, name);
    }

    private JsonNode data(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString()).get("data");
    }

    private MockHttpServletRequestBuilder codeRequest(String who, long device) {
        return get("/api/v1/admin/resources/" + device + "/check-in-code")
                .with(who.equals(admin) ? user(who).roles("ADMIN") : user(who));
    }

    private String code() throws Exception {
        return data(mvc.perform(codeRequest(admin, resource))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn())
                .get("checkInCode")
                .asText();
    }

    private MockHttpServletRequestBuilder check(String who, String token) {
        return post("/api/v1/bookings/" + order + "/check-in")
                .with(user(who))
                .with(csrf())
                .contentType("application/json")
                .content("{\"checkInCode\":\"" + token + "\"}");
    }

    private String state() {
        return jdbc.queryForObject(
                "SELECT status FROM booking_order WHERE order_no=?", String.class, order);
    }

    private long id() {
        return jdbc.queryForObject(
                "SELECT id FROM booking_order WHERE order_no=?", Long.class, order);
    }

    private int count(String table, String column, Object value) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Integer.class, value);
    }

    private int scan() {
        return expiry.scanDueOrders();
    }

    @Test
    void validCodeChecksInAtOpeningAndRepeatKeepsOriginalTimestamp() throws Exception {
        String token = code();
        mvc.perform(check(owner, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.status").value("CHECKED_IN"))
                .andExpect(jsonPath("data.checkedInAt").value(opening.toString()));
        when(clock.instant()).thenReturn(deadline);
        mvc.perform(check(owner, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.checkedInAt").value(opening.toString()));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_event WHERE"
                                        + " event_type='BOOKING_CHECKED_IN' AND aggregate_id=?",
                                Integer.class,
                                order))
                .isEqualTo(1);
        assertThat(count("user_slot_occupation", "booking_id", id())).isEqualTo(4);
    }

    @Test
    void codesAreAdminOnlyAndCurrentDatabaseRoleIsRechecked() throws Exception {
        mvc.perform(codeRequest(owner, resource)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/resources/" + resource + "/check-in-code"))
                .andExpect(status().isUnauthorized());
        jdbc.update("UPDATE app_user SET role='USER' WHERE username=?", admin);
        mvc.perform(codeRequest(admin, resource).with(user(admin).roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidExpiredAndWrongDeviceCodesAreRejected() throws Exception {
        String token = code();
        mvc.perform(check(owner, "bad"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("签到码无效或已过期，请刷新"))));
        mvc.perform(
                        check(
                                owner,
                                token.substring(0, token.length() / 2)
                                        + "x"
                                        + token.substring(token.length() / 2 + 1)))
                .andExpect(status().isUnprocessableEntity());
        when(clock.instant()).thenReturn(opening.plusSeconds(60));
        mvc.perform(check(owner, token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("签到码无效或已过期，请刷新"))));
        when(clock.instant()).thenReturn(opening);
        jdbc.update(
                "INSERT INTO resource(name,location) VALUES (?,'A202')",
                "other-device_" + UUID.randomUUID());
        long another = jdbc.queryForObject("SELECT MAX(id) FROM resource", Long.class);
        String wrong =
                data(mvc.perform(codeRequest(admin, another))
                                .andExpect(status().isOk())
                                .andReturn())
                        .get("checkInCode")
                        .asText();
        mvc.perform(check(owner, wrong)).andExpect(status().isUnprocessableEntity());
        assertThat(state()).isEqualTo("CONFIRMED");
    }

    @Test
    void checkInWindowRejectsEarlyAndAcceptsOneMillisecondBeforeDeadline() throws Exception {
        when(clock.instant()).thenReturn(opening.minusMillis(1));
        mvc.perform(check(owner, code()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(org.hamcrest.Matchers.isIn(java.util.List.of("尚未到签到时间"))));
        when(clock.instant()).thenReturn(deadline.minusMillis(1));
        mvc.perform(check(owner, code())).andExpect(status().isOk());
    }

    @Test
    void deadlineRejectsCheckInButCommitsNoShowAndOnlyOnePenalty() throws Exception {
        when(clock.instant()).thenReturn(deadline);
        String token = code();
        mvc.perform(check(owner, token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(org.hamcrest.Matchers.isIn(java.util.List.of("已超过签到截止时间"))));
        mvc.perform(check(owner, token)).andExpect(status().isUnprocessableEntity());
        assertThat(state()).isEqualTo("NO_SHOW");
        assertThat(count("credit_ledger", "business_id", Long.toString(id()))).isEqualTo(1);
        assertThat(count("release_job", "source_booking_id", id())).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT credit_score FROM app_user WHERE id=?",
                                Integer.class,
                                ownerId))
                .isEqualTo(90);
        assertThat(count("user_slot_occupation", "booking_id", id())).isEqualTo(1);
    }

    @Test
    void foreignDisabledAndDemoAccountsCannotCheckIn() throws Exception {
        String token = code();
        mvc.perform(check(other, token)).andExpect(status().isNotFound());
        mvc.perform(check(admin, token)).andExpect(status().isNotFound());
        jdbc.update("UPDATE app_user SET enabled=0 WHERE id=?", ownerId);
        mvc.perform(check(owner, token)).andExpect(status().isForbidden());
        jdbc.update("UPDATE app_user SET enabled=1,role='DEMO' WHERE id=?", ownerId);
        mvc.perform(check(owner, token)).andExpect(status().isForbidden());
        assertThat(state()).isEqualTo("CONFIRMED");
    }

    @Test
    void restrictionAndPausedResourceDoNotBlockExistingBookingCheckIn() throws Exception {
        jdbc.update(
                "UPDATE app_user SET credit_score=50,restriction_until='2099-01-01' WHERE id=?",
                ownerId);
        jdbc.update("UPDATE resource SET status='PAUSED' WHERE id=?", resource);
        mvc.perform(check(owner, code())).andExpect(status().isOk());
    }

    @Test
    void canceledOrderCannotCheckIn() throws Exception {
        mvc.perform(
                        post("/api/v1/bookings/" + order + "/cancel")
                                .with(user(owner))
                                .with(csrf())
                                .header("Idempotency-Key", "cancel")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isOk());
        mvc.perform(check(owner, code()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("当前订单不可签到", "退役设备不能重新开放日历"))));
    }

    @Test
    void scanNormalizesDisabledOwnersAndDoesNotChargeTwice() throws Exception {
        jdbc.update("UPDATE app_user SET enabled=0,credit_score=65 WHERE id=?", ownerId);
        when(clock.instant()).thenReturn(deadline);
        scan();
        scan();
        assertThat(state()).isEqualTo("NO_SHOW");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT credit_score FROM app_user WHERE id=?",
                                Integer.class,
                                ownerId))
                .isEqualTo(55);
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT restriction_until FROM app_user WHERE id=?",
                                        java.sql.Timestamp.class,
                                        ownerId)
                                .toInstant())
                .isEqualTo(deadline.plus(Duration.ofDays(7)));
        assertThat(count("credit_ledger", "business_id", Long.toString(id()))).isEqualTo(1);
        assertThat(count("release_job", "source_booking_id", id())).isEqualTo(1);
    }

    @Test
    void scanCompletesCheckedInOrderAtEndAndRetainsHistory() throws Exception {
        mvc.perform(check(owner, code())).andExpect(status().isOk());
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T03:00:00Z"));
        scan();
        scan();
        assertThat(state()).isEqualTo("COMPLETED");
        assertThat(count("credit_ledger", "business_id", Long.toString(id()))).isZero();
        assertThat(count("release_job", "source_booking_id", id())).isZero();
        assertThat(count("user_slot_occupation", "booking_id", id())).isEqualTo(4);
        mvc.perform(check(owner, "expired-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.checkedInAt").value(opening.toString()));
    }

    @Test
    void scanDoesNotProcessBeforeDeadlineAndChecksInCompletionLazily() throws Exception {
        when(clock.instant()).thenReturn(deadline.minusMillis(1));
        scan();
        assertThat(state()).isEqualTo("CONFIRMED");
        mvc.perform(check(owner, code())).andExpect(status().isOk());
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T03:00:00Z"));
        mvc.perform(check(owner, "already-checked-in"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.status").value("COMPLETED"));
    }

    @Test
    void eventFailureRollsBackCheckIn() throws Exception {
        String token = code();
        doThrow(new IllegalStateException("injected")).when(events).insert(any(OutboxEvent.class));
        mvc.perform(check(owner, token)).andExpect(status().isInternalServerError());
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT checked_in_at FROM booking_order WHERE order_no=?",
                                java.sql.Timestamp.class,
                                order))
                .isNull();
    }

    @Test
    void failureRollsBackNoShowAndNextScanRetries() throws Exception {
        when(clock.instant()).thenReturn(deadline);
        doThrow(new IllegalStateException("injected")).when(events).insert(any(OutboxEvent.class));
        scan();
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(count("credit_ledger", "business_id", Long.toString(id()))).isZero();
        assertThat(count("release_job", "source_booking_id", id())).isZero();
        assertThat(count("user_slot_occupation", "booking_id", id())).isEqualTo(4);
        reset(events);
        scan();
        assertThat(state()).isEqualTo("NO_SHOW");
    }

    @Test
    void concurrentCheckInsReturnSameTimeAndOnlyOneEvent() throws Exception {
        String token = code();
        List<Integer> statuses =
                race(
                        () ->
                                mvc.perform(check(owner, token))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus(),
                        () ->
                                mvc.perform(check(owner, token))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus());
        assertThat(statuses).containsOnly(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_event WHERE"
                                        + " event_type='BOOKING_CHECKED_IN' AND aggregate_id=?",
                                Integer.class,
                                order))
                .isEqualTo(1);
    }

    @Test
    void checkInRacingExpiryAtDeadlineCannotEscapePenalty() throws Exception {
        when(clock.instant()).thenReturn(deadline);
        String token = code();
        List<Integer> result =
                race(
                        () ->
                                mvc.perform(check(owner, token))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus(),
                        () -> {
                            scan();
                            return 0;
                        });
        assertThat(result).containsExactlyInAnyOrder(422, 0);
        assertThat(state()).isEqualTo("NO_SHOW");
        assertThat(count("credit_ledger", "business_id", Long.toString(id()))).isEqualTo(1);
    }

    @Test
    void twoExpiryWorkersHaveOnePenaltyAndOneRelease() throws Exception {
        when(clock.instant()).thenReturn(deadline);
        race(() -> scan(), () -> scan());
        assertThat(state()).isEqualTo("NO_SHOW");
        assertThat(count("credit_ledger", "business_id", Long.toString(id()))).isEqualTo(1);
        assertThat(count("release_job", "source_booking_id", id())).isEqualTo(1);
    }

    private String anotherBooking(int start, int end) throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-15T00:00:00Z"));
        var result =
                mvc.perform(
                                post("/api/v1/bookings")
                                        .with(user(owner))
                                        .with(csrf())
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType("application/json")
                                        .content(
                                                "{\"resourceId\":"
                                                        + resource
                                                        + ",\"bookDate\":\"2026-10-16\",\"startSlot\":"
                                                        + start
                                                        + ",\"endSlot\":"
                                                        + end
                                                        + ",\"purpose\":\"STUDY\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        return data(result).get("orderNo").asText();
    }

    @Test
    void failedOrderDoesNotPreventAnotherOrderFromCommittingInSameScan() throws Exception {
        String next = anotherBooking(22, 24);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T03:15:00Z"));
        doThrow(new IllegalStateException("one order failure"))
                .when(events)
                .insert(
                        argThat(
                                (OutboxEvent e) ->
                                        e != null
                                                && order.equals(e.getAggregateId())
                                                && "BOOKING_NO_SHOW".equals(e.getEventType())));
        scan();
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM booking_order WHERE order_no=?",
                                String.class,
                                next))
                .isEqualTo("NO_SHOW");
        assertThat(count("credit_ledger", "business_id", Long.toString(id()))).isZero();
        reset(events);
        scan();
        assertThat(state()).isEqualTo("NO_SHOW");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT credit_score FROM app_user WHERE id=?",
                                Integer.class,
                                ownerId))
                .isEqualTo(80);
    }

    @Test
    void concurrentDistinctOrdersForOneOwnerDoNotLoseCreditUpdates() throws Exception {
        // 使用另一台设备，确保两个事务只在用户锁相遇，不由同一日历锁提前串行。
        String name = "credit-device_" + UUID.randomUUID();
        jdbc.update("INSERT INTO resource(name,location) VALUES (?,'A202')", name);
        resource = jdbc.queryForObject("SELECT id FROM resource WHERE name=?", Long.class, name);
        jdbc.update(
                "INSERT INTO resource_day(resource_id,book_date) VALUES (?,'2026-10-16')",
                resource);
        String next = anotherBooking(22, 24);
        long nextId =
                jdbc.queryForObject(
                        "SELECT id FROM booking_order WHERE order_no=?", Long.class, next);
        jdbc.update("UPDATE app_user SET credit_score=65 WHERE id=?", ownerId);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T03:15:00Z"));
        race(() -> expiry.process(id()) ? 1 : 0, () -> expiry.process(nextId) ? 1 : 0);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT credit_score FROM app_user WHERE id=?",
                                Integer.class,
                                ownerId))
                .isEqualTo(45);
        assertThat(count("credit_ledger", "user_id", ownerId)).isEqualTo(2);
        assertThat(
                        jdbc.queryForList(
                                "SELECT score_before FROM credit_ledger WHERE user_id=? ORDER BY"
                                        + " id",
                                Integer.class,
                                ownerId))
                .containsExactly(65, 55);
    }

    @Test
    void scanLimitsBatchTo100AndContinuesRemainingOrdersNextRound() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-01T00:00:00Z"));
        // 排空其他用例留下的合法历史订单，以下断言只涉及本用例创建的 101 单。
        while (scan() > 0) {}
        String prefix = "batch_" + UUID.randomUUID() + "_";
        for (int i = 0; i < 101; i++) {
            long userId = account(prefix + i, "USER");
            jdbc.update("INSERT INTO resource(name,location) VALUES (?,'A201')", prefix + i);
            long device =
                    jdbc.queryForObject(
                            "SELECT id FROM resource WHERE name=?", Long.class, prefix + i);
            jdbc.update(
                    "INSERT INTO resource_day(resource_id,book_date) VALUES (?,'2026-10-16')",
                    device);
            jdbc.update(
                    """
INSERT INTO booking_order(order_no,user_id,resource_id,book_date,start_slot,end_slot,charged_slots,
    status,purpose,source,start_at,check_in_open_at,check_in_deadline,end_at)
SELECT ?,?,? ,book_date,start_slot,end_slot,end_slot-start_slot,'CONFIRMED',purpose,'DIRECT',
    start_at,check_in_open_at,check_in_deadline,end_at FROM booking_order WHERE order_no=?
""",
                    prefix + i,
                    userId,
                    device,
                    order);
            long booked =
                    jdbc.queryForObject(
                            "SELECT id FROM booking_order WHERE order_no=?",
                            Long.class,
                            prefix + i);
            for (int slot = 18; slot < 22; slot++) {
                jdbc.update(
                        "INSERT INTO"
                            + " slot_occupation(resource_id,book_date,slot_index,state,booking_id)"
                            + " VALUES (?,'2026-10-16',?,'BOOKED',?)",
                        device,
                        slot,
                        booked);
                jdbc.update(
                        "INSERT INTO user_slot_occupation(user_id,book_date,slot_index,booking_id)"
                                + " VALUES (?,'2026-10-16',?,?)",
                        userId,
                        slot,
                        booked);
            }
        }
        assertThat(scan()).isEqualTo(100);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM booking_order WHERE order_no LIKE ? AND"
                                        + " status='CONFIRMED'",
                                Integer.class,
                                prefix + "%"))
                .isEqualTo(1);
        assertThat(scan()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM booking_order WHERE order_no LIKE ? AND"
                                        + " status='NO_SHOW'",
                                Integer.class,
                                prefix + "%"))
                .isEqualTo(101);
    }

    private void failFirstScheduleWriteWithLockConflict() {
        doThrow(
                        new org.springframework.dao.PessimisticLockingFailureException(
                                "injected lock conflict"))
                .doCallRealMethod()
                .when(
                        org.springframework.test.util.AopTestUtils
                                .<ScheduleEventService>getUltimateTargetObject(scheduleEvents))
                .record(anyLong(), any(), anyLong(), any());
    }

    private MockHttpServletRequestBuilder retryBookingRequest() {
        return post("/api/v1/bookings")
                .with(user(owner))
                .with(csrf())
                .header("Idempotency-Key", "busy-booking")
                .contentType("application/json")
                .content(
                        "{\"resourceId\":"
                                + resource
                                + ",\"bookDate\":\"2026-10-16\",\"startSlot\":22,\"endSlot\":24,\"purpose\":\"STUDY\"}");
    }

    @Test
    void bookingLockConflictRollsBackAndSameKeyCanBeRetried() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-15T00:00:00Z"));
        failFirstScheduleWriteWithLockConflict();
        mvc.perform(retryBookingRequest()).andExpect(status().isServiceUnavailable());
        assertThat(count("booking_order", "resource_id", resource)).isEqualTo(1);
        assertThat(count("user_slot_occupation", "user_id", ownerId)).isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM api_idempotency WHERE user_id=? AND"
                                    + " request_key='busy-booking'",
                                Integer.class,
                                ownerId))
                .isZero();
        mvc.perform(retryBookingRequest()).andExpect(status().isCreated());
        mvc.perform(retryBookingRequest()).andExpect(status().isCreated());
        assertThat(count("booking_order", "resource_id", resource)).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revision FROM resource_day WHERE resource_id=? AND"
                                    + " book_date='2026-10-16'",
                                Long.class,
                                resource))
                .isEqualTo(2L);
    }

    @Test
    void checkInLockConflictRollsBackAndNextRequestSucceedsOnce() throws Exception {
        String token = code();
        failFirstScheduleWriteWithLockConflict();
        mvc.perform(check(owner, token)).andExpect(status().isServiceUnavailable());
        assertThat(state()).isEqualTo("CONFIRMED");
        mvc.perform(check(owner, token)).andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT version FROM booking_order WHERE order_no=?",
                                Long.class,
                                order))
                .isEqualTo(1L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_event WHERE"
                                    + " event_type='BOOKING_CHECKED_IN' AND aggregate_id=?",
                                Integer.class,
                                order))
                .isEqualTo(1);
    }

    private MockHttpServletRequestBuilder retryCancellationRequest() {
        return post("/api/v1/bookings/" + order + "/cancel")
                .with(user(owner))
                .with(csrf())
                .header("Idempotency-Key", "retry-cancel")
                .contentType("application/json")
                .content("{}");
    }

    @Test
    void cancellationLockConflictRollsBackAndOriginalKeyStillWorks() throws Exception {
        failFirstScheduleWriteWithLockConflict();
        mvc.perform(retryCancellationRequest()).andExpect(status().isServiceUnavailable());
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(count("release_job", "source_booking_id", id())).isZero();
        mvc.perform(retryCancellationRequest()).andExpect(status().isOk());
        mvc.perform(retryCancellationRequest()).andExpect(status().isOk());
        assertThat(count("release_job", "source_booking_id", id())).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM api_idempotency WHERE user_id=? AND"
                                    + " operation='CANCEL_BOOKING' AND request_key='retry-cancel'"
                                    + " AND result_http_status=200",
                                Integer.class,
                                ownerId))
                .isEqualTo(1);
    }

    @Test
    void expiryLockConflictRetriesNextScanWithoutDuplicatePenalty() {
        when(clock.instant()).thenReturn(deadline);
        failFirstScheduleWriteWithLockConflict();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> expiry.process(id()))
                .isInstanceOf(org.springframework.dao.PessimisticLockingFailureException.class);
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(count("credit_ledger", "business_id", Long.toString(id()))).isZero();
        assertThat(count("release_job", "source_booking_id", id())).isZero();
        scan();
        assertThat(state()).isEqualTo("NO_SHOW");
        assertThat(count("credit_ledger", "business_id", Long.toString(id()))).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT credit_score FROM app_user WHERE id=?",
                                Integer.class,
                                ownerId))
                .isEqualTo(90);
    }

    @Test
    void matchingLockConflictPersistsFailureAndRetriesAtNextHalfHour() throws Exception {
        mvc.perform(retryCancellationRequest()).andExpect(status().isOk());
        long job =
                jdbc.queryForObject(
                        "SELECT id FROM release_job WHERE source_booking_id=?", Long.class, id());
        failFirstScheduleWriteWithLockConflict();
        releases.process(job);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM release_job WHERE id=?", String.class, job))
                .isEqualTo("PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempts FROM release_job WHERE id=?", Integer.class, job))
                .isEqualTo(1);
        assertThat(count("slot_occupation", "release_job_id", job)).isEqualTo(4);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-16T01:00:00Z"));
        releases.process(job);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM release_job WHERE id=?", String.class, job))
                .isEqualTo("RELEASED");
        assertThat(count("slot_occupation", "release_job_id", job)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_event WHERE"
                                    + " event_type='RELEASE_COMPLETED' AND aggregate_id=(SELECT"
                                    + " job_no FROM release_job WHERE id=?)",
                                Integer.class,
                                job))
                .isEqualTo(1);
    }

    private List<Integer> race(Callable<Integer> a, Callable<Integer> b) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var x =
                    pool.submit(
                            () -> {
                                gate.await();
                                return a.call();
                            });
            var y =
                    pool.submit(
                            () -> {
                                gate.await();
                                return b.call();
                            });
            gate.countDown();
            return List.of(x.get(30, TimeUnit.SECONDS), y.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
