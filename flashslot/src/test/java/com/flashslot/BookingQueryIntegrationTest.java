package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.*;
import java.util.UUID;

/** 真实 MySQL 验证订单读取、归属隔离、管理员权限与稳定游标翻页。 */
@EnabledIfEnvironmentVariable(named = "FLASHSLOT_PRIVATE_TEST", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
class BookingQueryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean Clock clock;
    String username, other, admin;
    long uid, otherId, adminId, resource;

    private com.flashslot.support.TestUser user(String name) {
        return new com.flashslot.support.TestUser(jdbc, name);
    }

    @BeforeEach
    void fixtures() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-15T00:00:00Z"));
        username = "read_" + UUID.randomUUID();
        other = "read_" + UUID.randomUUID();
        admin = "read_" + UUID.randomUUID();
        uid = account(username, "USER");
        otherId = account(other, "USER");
        adminId = account(admin, "ADMIN");
        String name = "read_" + UUID.randomUUID();
        jdbc.update("INSERT INTO resource(name,location) VALUES (?,'实验室 A201')", name);
        resource = jdbc.queryForObject("SELECT id FROM resource WHERE name=?", Long.class, name);
        jdbc.update(
                "INSERT INTO resource_day(resource_id,book_date) VALUES (?,'2026-10-16')",
                resource);
    }

    @Test
    void createdBookingCanBeReadFromListAndDetail() throws Exception {
        var created =
                mvc.perform(
                                post("/api/v1/bookings")
                                        .with(user(username))
                                        .with(csrf())
                                        .header("Idempotency-Key", "create-and-read")
                                        .contentType("application/json")
                                        .content(
                                                "{\"resourceId\":"
                                                        + resource
                                                        + ",\"bookDate\":\"2026-10-16\",\"startSlot\":18,\"endSlot\":22,\"purpose\":\"EXPERIMENT\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String number =
                json.readTree(created.getResponse().getContentAsString())
                        .at("/data/orderNo")
                        .asText();
        var detail =
                mvc.perform(get("/api/v1/bookings/" + number).with(user(username)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("data.orderNo").value(number))
                        .andExpect(jsonPath("data.resourceLocation").value("实验室 A201"))
                        .andExpect(jsonPath("data.status").value("CONFIRMED"))
                        .andExpect(jsonPath("data.startAt").value("2026-10-16T01:00:00Z"))
                        .andExpect(jsonPath("data.checkInDeadline").value("2026-10-16T01:15:00Z"))
                        .andExpect(jsonPath("data.version").value(0))
                        .andExpect(jsonPath("data.releaseStatus").isEmpty())
                        .andReturn();
        JsonNode data = json.readTree(detail.getResponse().getContentAsString()).get("data");
        assertThat(data.has("id")).isFalse();
        assertThat(data.has("userId")).isFalse();
        assertThat(data.has("waitlistId")).isFalse();
        assertThat(data.has("releaseJobId")).isFalse();
        mvc.perform(get("/api/v1/me/bookings").with(user(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items.length()").value(1))
                .andExpect(jsonPath("data.items[0].orderNo").value(number))
                .andExpect(jsonPath("data.hasMore").value(false))
                .andExpect(jsonPath("data.nextCursor").isEmpty());
    }

    @Test
    void listOrdersByCreationTimeThenIdAndDoesNotSkipTies() throws Exception {
        String newest = booking(uid, "2026-10-15 00:01:00.000");
        String tiedFirst = booking(uid, "2026-10-15 00:00:00.000");
        String tiedSecond = booking(uid, "2026-10-15 00:00:00.000");
        String foreign = booking(otherId, "2026-10-15 00:02:00.000");
        var first =
                mvc.perform(
                                get("/api/v1/me/bookings")
                                        .with(user(username))
                                        .param("limit", "2")
                                        .param("userId", Long.toString(otherId)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("data.items.length()").value(2))
                        .andExpect(jsonPath("data.items[0].orderNo").value(newest))
                        .andExpect(jsonPath("data.items[1].orderNo").value(tiedSecond))
                        .andExpect(jsonPath("data.hasMore").value(true))
                        .andReturn();
        assertThat(first.getResponse().getContentAsString()).doesNotContain(foreign);
        String cursor =
                json.readTree(first.getResponse().getContentAsString())
                        .at("/data/nextCursor")
                        .asText();
        booking(uid, "2026-10-15 00:03:00.000"); // 翻页过程中新增的订单不挤占后一页。
        mvc.perform(
                        get("/api/v1/me/bookings")
                                .with(user(username))
                                .param("limit", "2")
                                .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items.length()").value(1))
                .andExpect(jsonPath("data.items[0].orderNo").value(tiedFirst))
                .andExpect(jsonPath("data.hasMore").value(false))
                .andExpect(jsonPath("data.nextCursor").isEmpty());
    }

    @Test
    void noOrdersIsAnEmptyPageAndInvalidPaginationIsRejected() throws Exception {
        mvc.perform(get("/api/v1/me/bookings").with(user(username)).param("cursor", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items").isEmpty())
                .andExpect(jsonPath("data.hasMore").value(false))
                .andExpect(jsonPath("data.nextCursor").isEmpty());
        for (String limit : new String[] {"0", "101", "-1", "abc"}) {
            mvc.perform(get("/api/v1/me/bookings").with(user(username)).param("limit", limit))
                    .andExpect(status().isBadRequest());
        }
        for (String cursor : new String[] {"bad cursor", "x".repeat(65), "BK_missing"}) {
            mvc.perform(get("/api/v1/me/bookings").with(user(username)).param("cursor", cursor))
                    .andExpect(status().isBadRequest());
        }
        String foreign = booking(otherId, "2026-10-15 00:00:00.000");
        mvc.perform(get("/api/v1/me/bookings").with(user(username)).param("cursor", foreign))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("分页游标无效", "分页游标无效，请从第一页重新查询"))));
    }

    @Test
    void otherUsersAndMissingOrdersReturnIdenticalNotFound() throws Exception {
        String foreign = booking(otherId, "2026-10-15 00:00:00.000");
        var forbidden =
                mvc.perform(get("/api/v1/bookings/" + foreign).with(user(username)))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("code").value(0))
                        .andExpect(
                                jsonPath("msg")
                                        .value(
                                                org.hamcrest.Matchers.isIn(
                                                        java.util.List.of("订单不存在"))))
                        .andReturn();
        var missing =
                mvc.perform(get("/api/v1/bookings/BK_missing").with(user(username)))
                        .andExpect(status().isNotFound())
                        .andReturn();
        assertThat(json.readTree(forbidden.getResponse().getContentAsString()))
                .isEqualTo(json.readTree(missing.getResponse().getContentAsString()));
    }

    @Test
    void administratorNeedsBothTrustedAuthorityAndCurrentDatabaseRole() throws Exception {
        String number = booking(uid, "2026-10-15 00:00:00.000");
        mvc.perform(get("/api/v1/bookings/" + number).with(user(admin).roles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/bookings/" + number).with(user(admin).roles("USER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/bookings/" + number).with(user(other).roles("ADMIN")))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE app_user SET role='USER' WHERE id=?", adminId);
        mvc.perform(get("/api/v1/bookings/" + number).with(user(admin).roles("ADMIN")))
                .andExpect(status().isNotFound());
        // /me 永远是当前账号，即使管理员也不能把它当作全局订单列表。
        mvc.perform(get("/api/v1/me/bookings").with(user(admin).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items").isEmpty());
    }

    @Test
    void anonymousDisabledMissingAndDemoAccountsCannotRead() throws Exception {
        String number = booking(uid, "2026-10-15 00:00:00.000");
        for (String path : new String[] {"/api/v1/me/bookings", "/api/v1/bookings/" + number}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(user("missing_" + UUID.randomUUID())))
                    .andExpect(status().isForbidden());
        }
        jdbc.update("UPDATE app_user SET enabled=0 WHERE id=?", uid);
        mvc.perform(get("/api/v1/me/bookings").with(user(username)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(org.hamcrest.Matchers.isIn(java.util.List.of("账号已停用"))));
        mvc.perform(get("/api/v1/bookings/" + number).with(user(username)))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE app_user SET enabled=1,role='DEMO' WHERE id=?", uid);
        mvc.perform(get("/api/v1/me/bookings").with(user(username)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/bookings/" + number).with(user(username)))
                .andExpect(status().isForbidden());
    }

    @Test
    void restrictionsAndInactiveResourceDoNotHideExistingOrders() throws Exception {
        String number = booking(uid, "2026-10-15 00:00:00.000");
        jdbc.update(
                "UPDATE app_user SET restriction_until='2099-01-01',credit_score=0 WHERE id=?",
                uid);
        jdbc.update("UPDATE resource SET status='RETIRED' WHERE id=?", resource);
        mvc.perform(get("/api/v1/bookings/" + number).with(user(username)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/bookings").with(user(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items[0].orderNo").value(number));
    }

    @Test
    void readsPersistedCurrentStateWithoutMutatingExpiredOrders() throws Exception {
        String number = booking(uid, "2026-10-15 00:00:00.000");
        when(clock.instant()).thenReturn(Instant.parse("2026-12-01T00:00:00Z"));
        mvc.perform(get("/api/v1/bookings/" + number).with(user(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.status").value("CONFIRMED"));
        jdbc.update(
                """
                UPDATE booking_order SET status='CANCELED',terminated_at='2026-10-15 00:10:00',
                  termination_reason='ADMIN_CANCEL',reason_text='设备维护',version=1 WHERE order_no=?
                """,
                number);
        mvc.perform(get("/api/v1/bookings/" + number).with(user(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.status").value("CANCELED"))
                .andExpect(jsonPath("data.terminationReason").value("ADMIN_CANCEL"))
                .andExpect(jsonPath("data.reasonText").value("设备维护"))
                .andExpect(jsonPath("data.terminatedAt").value("2026-10-15T00:10:00Z"))
                .andExpect(jsonPath("data.version").value(1));
        mvc.perform(get("/api/v1/me/bookings").with(user(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items[0].status").value("CANCELED"));
    }

    @Test
    void releaseProgressBelongsToSourceOrderAndDoesNotExposeInternalError() throws Exception {
        String number = booking(uid, "2026-10-15 00:00:00.000");
        long bookingId =
                jdbc.queryForObject(
                        "SELECT id FROM booking_order WHERE order_no=?", Long.class, number);
        jdbc.update(
                "UPDATE booking_order SET"
                    + " status='CANCELED',terminated_at='2026-10-15',termination_reason='USER_CANCEL'"
                    + " WHERE id=?",
                bookingId);
        String job = "RJ_" + UUID.randomUUID();
        jdbc.update(
                """
INSERT INTO release_job(job_no,source_booking_id,resource_id,book_date,start_slot,end_slot,effective_start_slot,next_run_at,last_error)
VALUES (?,?,?,'2026-10-16',18,22,18,'2026-10-15','private-stacktrace')
""",
                job,
                bookingId,
                resource);
        var result =
                mvc.perform(get("/api/v1/bookings/" + number).with(user(username)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("data.releaseStatus").value("PENDING"))
                        .andExpect(jsonPath("data.releaseJobNo").value(job))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("private-stacktrace", "lastError", "attempts");
        jdbc.update("UPDATE release_job SET status='RELEASED' WHERE job_no=?", job);
        mvc.perform(get("/api/v1/bookings/" + number).with(user(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.releaseStatus").value("RELEASED"));
    }

    private long account(String name, String role) {
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,role) VALUES (?,'private-hash',?)",
                name,
                role);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, name);
    }

    /** 查询夹具覆盖历史状态，不通过创建接口限制来构造只读场景。 */
    private String booking(long userId, String createdAt) {
        String number = "BK_" + UUID.randomUUID();
        jdbc.update(
                """
INSERT INTO booking_order(order_no,user_id,resource_id,book_date,start_slot,end_slot,charged_slots,purpose,
  start_at,check_in_open_at,check_in_deadline,end_at,created_at)
VALUES (?,?,?,'2026-10-16',18,22,4,'EXPERIMENT',
  '2026-10-16 01:00:00','2026-10-16 00:50:00','2026-10-16 01:15:00','2026-10-16 03:00:00',?)
""",
                number,
                userId,
                resource,
                createdAt);
        return number;
    }
}
