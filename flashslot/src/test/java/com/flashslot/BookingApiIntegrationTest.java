package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.booking.mapper.BookingMapper;
import com.flashslot.common.mapper.ApiIdempotencyMapper;
import com.flashslot.messaging.mapper.OutboxEventMapper;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 使用独立 MySQL 验证真实 HTTP、行锁、唯一键及事务原子性。 */
@EnabledIfEnvironmentVariable(named = "FLASHSLOT_PRIVATE_TEST", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
class BookingApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired org.mybatis.spring.SqlSessionTemplate sessions;
    @MockitoBean Clock clock;
    @MockitoSpyBean OutboxEventMapper events;
    @MockitoSpyBean BookingMapper bookings;
    @MockitoSpyBean AccountPermissionMapper accounts;
    @MockitoSpyBean ApiIdempotencyMapper idempotency;
    final Instant now = Instant.parse("2026-10-15T00:00:00Z");
    long resource, secondResource, uid;
    String username, other;

    private com.flashslot.support.TestUser user(String name) {
        return new com.flashslot.support.TestUser(jdbc, name);
    }

    @BeforeEach
    void fixtures() {
        when(clock.instant()).thenReturn(now);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.withZone(any())).thenAnswer(c -> Clock.fixed(now, c.getArgument(0)));
        username = "bk_" + UUID.randomUUID();
        other = "bk_" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app_user(username,password_hash) VALUES (?,'test'),(?,'test')",
                username,
                other);
        uid = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
        resource = resource();
        secondResource = resource();
    }

    long resource() {
        String name = "bk_" + UUID.randomUUID();
        jdbc.update("INSERT INTO resource(name,location) VALUES (?,'A201')", name);
        long id = jdbc.queryForObject("SELECT id FROM resource WHERE name=?", Long.class, name);
        jdbc.update(
                "INSERT INTO resource_day(resource_id,book_date) VALUES"
                        + " (?,'2026-10-15'),(?,'2026-10-16'),(?,'2026-10-21'),(?,'2026-10-22')",
                id,
                id,
                id,
                id);
        return id;
    }

    MockHttpServletRequestBuilder request(
            String who, String key, long device, String date, int start, int end) {
        return post("/api/v1/bookings")
                .with(user(who))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(
                        "{\"resourceId\":"
                                + device
                                + ",\"bookDate\":\""
                                + date
                                + "\",\"startSlot\":"
                                + start
                                + ",\"endSlot\":"
                                + end
                                + ",\"purpose\":\"EXPERIMENT\"}");
    }

    MockHttpServletRequestBuilder request(String key, int start, int end) {
        return request(username, key, resource, "2026-10-16", start, end);
    }

    int count(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM "
                        + table
                        + " WHERE "
                        + (table.equals("user_slot_occupation") ? "user_id" : "resource_id")
                        + "=?",
                Integer.class,
                table.equals("user_slot_occupation") ? uid : resource);
    }

    @Test
    void createsCompleteBookingAndScheduleThenReplaysOriginalSnapshot() throws Exception {
        var result =
                mvc.perform(request("one", 18, 22))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("data.status").value("CONFIRMED"))
                        .andExpect(jsonPath("data.startAt").value("2026-10-16T01:00:00Z"))
                        .andExpect(jsonPath("data.checkInOpenAt").value("2026-10-16T00:50:00Z"))
                        .andExpect(jsonPath("data.checkInDeadline").value("2026-10-16T01:15:00Z"))
                        .andReturn();
        String order =
                json.readTree(result.getResponse().getContentAsString())
                        .at("/data/orderNo")
                        .asText();
        assertThat(count("booking_order")).isEqualTo(1);
        assertThat(count("slot_occupation")).isEqualTo(4);
        assertThat(count("user_slot_occupation")).isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",
                                Integer.class,
                                order))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_event WHERE"
                                        + " event_type='SCHEDULE_CHANGED' AND aggregate_id=?",
                                Integer.class,
                                resource + ":2026-10-16"))
                .isEqualTo(1);
        mvc.perform(
                        get("/api/v1/resources/" + resource + "/schedule")
                                .param("date", "2026-10-16")
                                .with(user(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.revision").value(1))
                .andExpect(jsonPath("data.slots[18].orderNo").value(order))
                .andExpect(jsonPath("data.slots[21].state").value("BOOKED"));
        jdbc.update(
                "UPDATE booking_order SET"
                    + " status='CANCELED',terminated_at='2026-10-15',termination_reason='USER_CANCEL'"
                    + " WHERE order_no=?",
                order);
        var replay =
                mvc.perform(request("one", 18, 22)).andExpect(status().isCreated()).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()))
                .isEqualTo(json.readTree(result.getResponse().getContentAsString()));
        mvc.perform(request("one", 22, 24))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("此请求键已用于其他参数"))));
    }

    @Test
    void competingUsersCannotLeavePartialOrdersOrSlots() throws Exception {
        var results =
                concurrently(
                        request("a", 18, 22), request(other, "b", resource, "2026-10-16", 20, 24));
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).sorted().toList())
                .containsExactly(201, 409);
        assertThat(count("booking_order")).isEqualTo(1);
        assertThat(count("slot_occupation")).isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM user_slot_occupation WHERE booking_id IN"
                                        + " (SELECT id FROM booking_order WHERE resource_id=?)",
                                Integer.class,
                                resource))
                .isEqualTo(4);
    }

    @Test
    void concurrentSameKeyCreatesExactlyOneOrder() throws Exception {
        var results = concurrently(request("same", 18, 22), request("same", 18, 22));
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).toList())
                .containsOnly(201);
        assertThat(json.readTree(results.get(0).getResponse().getContentAsString()))
                .isEqualTo(json.readTree(results.get(1).getResponse().getContentAsString()));
        assertThat(count("booking_order")).isEqualTo(1);
    }

    @Test
    void sameUserAcrossDevicesCannotOverlap() throws Exception {
        var results =
                concurrently(
                        request("a", 18, 22),
                        request(username, "b", secondResource, "2026-10-16", 20, 24));
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).sorted().toList())
                .containsExactly(201, 409);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM booking_order WHERE user_id=?",
                                Integer.class,
                                uid))
                .isEqualTo(1);
        assertThat(count("user_slot_occupation")).isEqualTo(4);
    }

    @Test
    void eventFailureRollsBackEverythingAndSameKeyCanRetry() throws Exception {
        doThrow(new IllegalStateException("injected event failure"))
                .when(events)
                .insert(any(com.flashslot.messaging.entity.OutboxEvent.class));
        mvc.perform(request("rollback", 18, 22)).andExpect(status().isInternalServerError());
        assertThat(count("booking_order")).isZero();
        assertThat(count("slot_occupation")).isZero();
        assertThat(count("user_slot_occupation")).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM api_idempotency WHERE user_id=?",
                                Integer.class,
                                uid))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revision FROM resource_day WHERE resource_id=? AND"
                                        + " book_date='2026-10-16'",
                                Long.class,
                                resource))
                .isZero();
        reset(events);
        mvc.perform(request("rollback", 18, 22)).andExpect(status().isCreated());
    }

    @Test
    void cutoffAndSevenDayWindowUseBusinessTime() throws Exception {
        mvc.perform(request(username, "cutoff", resource, "2026-10-15", 17, 18))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("已到申请截止时间", "已到预约截止时间"))));
        when(clock.instant()).thenReturn(now.minusMillis(1));
        mvc.perform(request(username, "before", resource, "2026-10-15", 17, 18))
                .andExpect(status().isCreated());
        when(clock.instant()).thenReturn(now);
        mvc.perform(request(username, "last", resource, "2026-10-21", 18, 19))
                .andExpect(status().isCreated());
        mvc.perform(request(username, "outside", resource, "2026-10-22", 18, 19))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validatesInputsAndIdentity() throws Exception {
        mvc.perform(request("long", 16, 25)).andExpect(status().isBadRequest());
        mvc.perform(request("reverse", 20, 18)).andExpect(status().isBadRequest());
        mvc.perform(request("bad key 中", 18, 20)).andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/v1/bookings")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(request("noCsrf", 18, 20).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE app_user SET enabled=0 WHERE id=?", uid);
        mvc.perform(request("disabled", 18, 20)).andExpect(status().isForbidden());
        assertThat(count("booking_order")).isZero();
    }

    @Test
    void restrictionExpiryRatherThanCreditScoreControlsAdmission() throws Exception {
        jdbc.update(
                "UPDATE app_user SET credit_score=50,restriction_until='2026-10-15 00:00:00.001'"
                        + " WHERE id=?",
                uid);
        mvc.perform(request("restricted", 18, 20))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("账号当前处于预约限制期"))));
        jdbc.update("UPDATE app_user SET restriction_until='2026-10-15 00:00:00' WHERE id=?", uid);
        mvc.perform(request("expired", 18, 20)).andExpect(status().isCreated());
    }

    @Test
    void businessFailureIsReplayedEvenAfterCalendarChanges() throws Exception {
        jdbc.update("UPDATE resource_day SET enabled=0 WHERE resource_id=?", resource);
        mvc.perform(request("closed", 18, 20))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(org.hamcrest.Matchers.isIn(java.util.List.of("设备未开放所选区间"))));
        jdbc.update("UPDATE resource_day SET enabled=1 WHERE resource_id=?", resource);
        mvc.perform(request("closed", 18, 20))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(org.hamcrest.Matchers.isIn(java.util.List.of("设备未开放所选区间"))));
        mvc.perform(request("new", 18, 20)).andExpect(status().isCreated());
    }

    @Test
    void adjacentIntervalCannotExceedConfirmedDailyQuota() throws Exception {
        mvc.perform(request("first", 16, 24)).andExpect(status().isCreated());
        mvc.perform(request("adjacent", 24, 25)).andExpect(status().isConflict());
        assertThat(count("user_slot_occupation")).isEqualTo(8);
    }

    @Test
    void pausedClosedMissingAndOutOfRangeDevicesCannotBeBooked() throws Exception {
        jdbc.update("UPDATE resource SET status='PAUSED' WHERE id=?", resource);
        mvc.perform(request("paused", 18, 20)).andExpect(status().isConflict());
        jdbc.update("UPDATE resource SET status='ACTIVE' WHERE id=?", resource);
        mvc.perform(request("early", 15, 18)).andExpect(status().isConflict());
        jdbc.update(
                "DELETE FROM resource_day WHERE resource_id=? AND book_date='2026-10-16'",
                resource);
        mvc.perform(request("missing-day", 18, 20))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("该日期日历尚未生成，请稍后重试", "预约日历尚未生成"))));
        mvc.perform(request(username, "missing-resource", Long.MAX_VALUE, "2026-10-16", 18, 20))
                .andExpect(status().isNotFound());
        assertThat(count("booking_order")).isZero();
    }

    @Test
    void bookingWithdrawsOnlyOwnExactlyMatchingWaitlist() throws Exception {
        jdbc.update(
                """
INSERT INTO waitlist_record(waitlist_no,user_id,resource_id,book_date,start_slot,end_slot,purpose,expire_at)
VALUES (?,?,?,'2026-10-16',18,20,'EXPERIMENT','2026-10-16 00:50:00'),
       (?,?,?,'2026-10-16',18,22,'EXPERIMENT','2026-10-16 00:50:00')
""",
                "WL_" + UUID.randomUUID(),
                uid,
                resource,
                "WL_" + UUID.randomUUID(),
                uid,
                resource);
        mvc.perform(request("match", 18, 20)).andExpect(status().isCreated());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM waitlist_record WHERE user_id=? AND"
                                        + " end_slot=20",
                                String.class,
                                uid))
                .isEqualTo("CANCELED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM waitlist_record WHERE user_id=? AND"
                                        + " end_slot=22",
                                String.class,
                                uid))
                .isEqualTo("WAITING");
    }

    @Test
    void idempotencyKeyIsScopedByUserAndPurposeParticipatesInHash() throws Exception {
        mvc.perform(request("shared", 18, 20)).andExpect(status().isCreated());
        mvc.perform(request(other, "shared", resource, "2026-10-16", 20, 22))
                .andExpect(status().isCreated());
        mvc.perform(
                        request("shared", 18, 20)
                                .content(
                                        "{\"resourceId\":"
                                                + resource
                                                + ",\"bookDate\":\"2026-10-16\",\"startSlot\":18,\"endSlot\":20,\"purpose\":\"STUDY\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("此请求键已用于其他参数"))));
    }

    @Test
    void finalUniqueConstraintFailureRollsBackOrderAndBothOccupations() throws Exception {
        // 在最后写入边界模拟一个绕过锁协议的占用，触发真实 MySQL 联合主键异常。
        doAnswer(
                        call -> {
                            com.flashslot.booking.entity.BookingOrder order = call.getArgument(0);
                            jdbc.update(
                                    "INSERT INTO"
                                        + " user_slot_occupation(user_id,book_date,slot_index,booking_id)"
                                        + " VALUES (?,?,?,?)",
                                    order.getUserId(),
                                    order.getBookDate(),
                                    order.getStartSlot(),
                                    order.getId());
                            return sessions.getMapper(BookingMapper.class)
                                    .occupyUser(order, call.getArgument(1));
                        })
                .when(bookings)
                .occupyUser(any(), anyList());
        mvc.perform(request("unique", 18, 22))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("所选设备或用户时段已被占用，请刷新后重试"))));
        assertNoBookingWrites();
    }

    @Test
    void legacyIdempotencySnapshotsReplayThroughCurrentResultContract() throws Exception {
        var first =
                mvc.perform(request("legacy-success", 18, 22))
                        .andExpect(status().isCreated())
                        .andReturn();
        var payload = json.readTree(first.getResponse().getContentAsString()).get("data");
        var legacy = json.createObjectNode().put("code", "SUCCESS").put("message", "操作成功");
        legacy.set("data", payload);
        jdbc.update(
                "UPDATE api_idempotency SET result_json=? WHERE user_id=? AND"
                    + " operation='CREATE_BOOKING' AND request_key='legacy-success'",
                legacy.toString(),
                uid);
        mvc.perform(request("legacy-success", 18, 22))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("code").value(1))
                .andExpect(jsonPath("msg").isEmpty())
                .andExpect(jsonPath("message").doesNotExist())
                .andExpect(jsonPath("data.orderNo").value(payload.get("orderNo").asText()));
        jdbc.update("UPDATE resource SET status='PAUSED' WHERE id=?", resource);
        mvc.perform(request("legacy-rejection", 22, 24)).andExpect(status().isConflict());
        jdbc.update(
                "UPDATE api_idempotency SET result_json=? WHERE user_id=? AND"
                    + " operation='CREATE_BOOKING' AND request_key='legacy-rejection'",
                "{\"code\":\"RESOURCE_CLOSED\",\"message\":\"设备未开放所选区间\",\"data\":null}",
                uid);
        jdbc.update("UPDATE resource SET status='ACTIVE' WHERE id=?", resource);
        mvc.perform(request("legacy-rejection", 22, 24))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value(0))
                .andExpect(jsonPath("msg").value("设备未开放所选区间"));
        assertThat(count("booking_order")).isEqualTo(1);
    }

    @Test
    void failureSavingIdempotencyResultRollsBackEvenBothOutboxEvents() throws Exception {
        doThrow(new IllegalStateException("injected result save failure"))
                .when(idempotency)
                .completeOperation(
                        anyLong(), anyString(), anyString(), anyInt(), anyString(), anyString());
        mvc.perform(request("result-failure", 18, 22)).andExpect(status().isInternalServerError());
        assertNoBookingWrites();
    }

    @Test
    void timeIsRecheckedAfterActuallyWaitingForUserLock() throws Exception {
        var reachedUserLock = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            reachedUserLock.countDown();
                            return sessions.getMapper(AccountPermissionMapper.class)
                                    .lockBookingAccount(uid);
                        })
                .when(accounts)
                .lockBookingAccount(uid);
        var pool = Executors.newSingleThreadExecutor();
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement =
                        connection.prepareStatement(
                                "SELECT id FROM app_user WHERE id=? FOR UPDATE")) {
                    statement.setLong(1, uid);
                    statement.executeQuery().close();
                }
                var result =
                        pool.submit(
                                () ->
                                        mvc.perform(
                                                        request(
                                                                username,
                                                                "wait",
                                                                resource,
                                                                "2026-10-15",
                                                                18,
                                                                20))
                                                .andReturn());
                assertThat(reachedUserLock.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(result.isDone()).isFalse();
                when(clock.instant()).thenReturn(Instant.parse("2026-10-15T00:30:00Z"));
                connection.commit();
                var response = result.get(10, TimeUnit.SECONDS).getResponse();
                assertThat(response.getStatus()).isEqualTo(409);
                assertThat(json.readTree(response.getContentAsString()).get("code").asInt())
                        .isZero();
                assertThat(json.readTree(response.getContentAsString()).get("msg").asText())
                        .isEqualTo("已到预约截止时间");
                assertThat(count("booking_order")).isZero();
            } finally {
                connection.rollback();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twentyConcurrentRetriesHaveOneOrderAndIdenticalResults() throws Exception {
        var pool = Executors.newFixedThreadPool(20);
        var start = new CountDownLatch(1);
        try {
            var results = new ArrayList<Future<MvcResult>>();
            for (int i = 0; i < 20; i++)
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return mvc.perform(request("twenty", 18, 22)).andReturn();
                                }));
            start.countDown();
            var bodies = new HashSet<String>();
            for (var result : results) {
                var response = result.get(15, TimeUnit.SECONDS).getResponse();
                assertThat(response.getStatus()).isEqualTo(201);
                bodies.add(response.getContentAsString());
            }
            assertThat(bodies).hasSize(1);
            assertThat(count("booking_order")).isEqualTo(1);
            assertThat(count("slot_occupation")).isEqualTo(4);
            assertThat(count("user_slot_occupation")).isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void demoAccountCannotCreateBooking() throws Exception {
        jdbc.update("UPDATE app_user SET role='DEMO' WHERE id=?", uid);
        mvc.perform(request("demo", 18, 20)).andExpect(status().isForbidden());
        assertThat(count("booking_order")).isZero();
    }

    /** 所有提交阶段异常均不能留下订单、占用、排期版本、事件或幂等结果。 */
    void assertNoBookingWrites() {
        assertThat(count("booking_order")).isZero();
        assertThat(count("slot_occupation")).isZero();
        assertThat(count("user_slot_occupation")).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM api_idempotency WHERE user_id=?",
                                Integer.class,
                                uid))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revision FROM resource_day WHERE resource_id=? AND"
                                        + " book_date='2026-10-16'",
                                Long.class,
                                resource))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? OR"
                                        + " JSON_EXTRACT(payload,'$.booking.resourceId')=?",
                                Integer.class,
                                resource + ":2026-10-16",
                                resource))
                .isZero();
    }

    List<MvcResult> concurrently(MockHttpServletRequestBuilder a, MockHttpServletRequestBuilder b)
            throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return mvc.perform(a).andReturn();
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return mvc.perform(b).andReturn();
                            });
            start.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
