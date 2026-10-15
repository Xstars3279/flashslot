package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.operations.entity.AdminAudit;
import com.flashslot.operations.mapper.AdminAuditMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;

/** HTTP 安全过滤器、Service 事务与真实私有 MySQL 联合验证。 */
@EnabledIfEnvironmentVariable(named = "FLASHSLOT_PRIVATE_TEST", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
class ScheduleApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean Clock clock;
    @MockitoSpyBean AdminAuditMapper audit;
    final Instant now = Instant.parse("2026-10-15T00:00:00Z");
    final LocalDate date = LocalDate.of(2026, 10, 16);
    long resourceId, adminId, otherId;
    String admin, other;

    private com.flashslot.support.TestUser user(String name) {
        return new com.flashslot.support.TestUser(jdbc, name);
    }

    /** 每个测试独立创建账号与设备，固定上海时间 10 月 15 日 08:00。 */
    @BeforeEach
    void fixtures() {
        when(clock.instant()).thenReturn(now);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.withZone(any())).thenAnswer(call -> Clock.fixed(now, call.getArgument(0)));
        admin = "calendar_admin_" + UUID.randomUUID();
        other = "calendar_user_" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,role) VALUES"
                    + " (?,'test','ADMIN'),(?,'test','USER')",
                admin,
                other);
        adminId =
                jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, admin);
        otherId =
                jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, other);
        String name = "calendar_" + UUID.randomUUID();
        jdbc.update("INSERT INTO resource(name,location) VALUES (?,'A201')", name);
        resourceId = jdbc.queryForObject("SELECT id FROM resource WHERE name=?", Long.class, name);
        jdbc.update(
                "INSERT INTO resource_day(resource_id,book_date) VALUES (?,?),(?,?)",
                resourceId,
                date,
                resourceId,
                date.minusDays(1));
    }

    /** 修改请求中的 revision 位于 JSON DTO，管理身份由安全测试工具注入。 */
    MockHttpServletRequestBuilder update(boolean enabled, int start, int end) {
        return put("/api/v1/admin/resources/" + resourceId + "/days/" + date)
                .with(user(admin).roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                        "{\"revision\":0,\"enabled\":"
                                + enabled
                                + ",\"openStartSlot\":"
                                + start
                                + ",\"openEndSlot\":"
                                + end
                                + ",\"reason\":\"调整开放时间\"}");
    }

    /** 创建半小时订单，返回 ID；仅用于排期与冲突测试，不实现预约入口。 */
    long booking(long userId, int slot) {
        String number = "BK_" + UUID.randomUUID();
        Instant start =
                date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().plusSeconds(slot * 1800L);
        jdbc.update(
                """
INSERT INTO booking_order(order_no,user_id,resource_id,book_date,start_slot,end_slot,charged_slots,purpose,
  start_at,check_in_open_at,check_in_deadline,end_at)
VALUES (?,?,?,?,?,?,1,'EXPERIMENT',?,?,?,?)
""",
                number,
                userId,
                resourceId,
                date,
                slot,
                slot + 1,
                java.sql.Timestamp.from(start),
                java.sql.Timestamp.from(start.minusSeconds(600)),
                java.sql.Timestamp.from(start.plusSeconds(900)),
                java.sql.Timestamp.from(start.plusSeconds(1800)));
        return jdbc.queryForObject(
                "SELECT id FROM booking_order WHERE order_no=?", Long.class, number);
    }

    @Test
    void publicScheduleHas48SlotsAndNeverLeaksOtherOrders() throws Exception {
        long mine = booking(adminId, 18), theirs = booking(otherId, 19);
        jdbc.update(
                "INSERT INTO slot_occupation VALUES"
                    + " (?,?,18,'BOOKED',?,NULL),(?,?,19,'BOOKED',?,NULL)",
                resourceId,
                date,
                mine,
                resourceId,
                date,
                theirs);
        String ownNo =
                jdbc.queryForObject(
                        "SELECT order_no FROM booking_order WHERE id=?", String.class, mine);
        String otherNo =
                jdbc.queryForObject(
                        "SELECT order_no FROM booking_order WHERE id=?", String.class, theirs);
        var publicResult =
                mvc.perform(
                                get("/api/v1/resources/" + resourceId + "/schedule")
                                        .param("date", date.toString()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("code").value(1))
                        .andExpect(jsonPath("data.slots.length()").value(48))
                        .andExpect(jsonPath("data.slots[0].state").value("CLOSED"))
                        .andExpect(jsonPath("data.slots[18].state").value("BOOKED"))
                        .andExpect(jsonPath("data.slots[18].mine").value(false))
                        .andExpect(jsonPath("data.slots[18].orderNo").isEmpty())
                        .andExpect(jsonPath("data.slots[22].canBook").value(true))
                        .andReturn();
        assertThat(publicResult.getResponse().getContentAsString())
                .doesNotContain(ownNo, otherNo, admin, other);
        var ownResult =
                mvc.perform(
                                get("/api/v1/resources/" + resourceId + "/schedule")
                                        .param("date", date.toString())
                                        .with(user(admin).roles("ADMIN")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("data.slots[18].mine").value(true))
                        .andExpect(jsonPath("data.slots[18].orderNo").value(ownNo))
                        .andExpect(jsonPath("data.slots[19].orderNo").isEmpty())
                        .andReturn();
        assertThat(ownResult.getResponse().getContentAsString()).doesNotContain(otherNo);
    }

    @Test
    void cutoffAndPausedDeviceAreSeparateFromFreeState() throws Exception {
        mvc.perform(
                        get("/api/v1/resources/" + resourceId + "/schedule")
                                .param("date", "2026-10-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.slots[15].state").value("PAST"))
                .andExpect(jsonPath("data.slots[17].state").value("FREE"))
                .andExpect(jsonPath("data.slots[17].canBook").value(false))
                .andExpect(jsonPath("data.slots[18].canBook").value(true));
        jdbc.update("UPDATE resource SET status='PAUSED' WHERE id=?", resourceId);
        mvc.perform(
                        get("/api/v1/resources/" + resourceId + "/schedule")
                                .param("date", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.slots[18].state").value("FREE"))
                .andExpect(jsonPath("data.slots[18].canBook").value(false));
    }

    @Test
    void updatePersistsRevisionOutboxAndAuditTogether() throws Exception {
        mvc.perform(update(false, 16, 44))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.revision").value(1));
        mvc.perform(update(false, 16, 44))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of(
                                                        "日历已修改，请刷新后重试",
                                                        "日历版本冲突，请刷新后重试",
                                                        "设备配置已被修改，请刷新后重试",
                                                        "设备配置版本不一致，请刷新后重试"))));
        mvc.perform(
                        get("/api/v1/resources/" + resourceId + "/schedule")
                                .param("date", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.slots[18].state").value("CLOSED"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM admin_audit WHERE"
                                    + " operation='RESOURCE_DAY_UPDATE' AND target_id=?",
                                Integer.class,
                                resourceId + ":" + date))
                .isEqualTo(1);
        String payload =
                jdbc.queryForObject(
                        "SELECT payload FROM outbox_event WHERE event_type='SCHEDULE_CHANGED' AND"
                            + " aggregate_id=?",
                        String.class,
                        resourceId + ":" + date);
        assertThat(json.readTree(payload).get("revision").asLong()).isEqualTo(1);
        assertThat(json.readTree(payload).get("bookDate").asText()).isEqualTo(date.toString());
    }

    @Test
    void activeBookingBlocksShrinkButAllowsExpansion() throws Exception {
        booking(otherId, 18);
        mvc.perform(update(true, 18, 44))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of(
                                                        "当日仍有有效预约、候补或交接占用，不能关闭或缩短开放范围"))));
        mvc.perform(update(true, 14, 46)).andExpect(status().isOk());
    }

    @Test
    void waitingCandidatePreventsClosing() throws Exception {
        jdbc.update(
                """
INSERT INTO waitlist_record(waitlist_no,user_id,resource_id,book_date,start_slot,end_slot,purpose,expire_at)
VALUES (?,?,?,?,18,20,'EXPERIMENT','2026-10-16 00:50:00')
""",
                "WL_" + UUID.randomUUID(),
                otherId,
                resourceId,
                date);
        mvc.perform(update(false, 16, 44))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of(
                                                        "当日仍有有效预约、候补或交接占用，不能关闭或缩短开放范围"))));
    }

    @Test
    void handoffOccupationIsVisibleAndPreventsClosing() throws Exception {
        long source = booking(otherId, 20);
        jdbc.update(
                "UPDATE booking_order SET"
                    + " status='CANCELED',terminated_at=?,termination_reason='USER_CANCEL' WHERE"
                    + " id=?",
                java.sql.Timestamp.from(now),
                source);
        String number = "RJ_" + UUID.randomUUID();
        jdbc.update(
                """
INSERT INTO release_job(job_no,source_booking_id,resource_id,book_date,start_slot,end_slot,effective_start_slot,next_run_at)
VALUES (?,?,?,?,20,21,20,?)
""",
                number,
                source,
                resourceId,
                date,
                java.sql.Timestamp.from(now));
        long job =
                jdbc.queryForObject(
                        "SELECT id FROM release_job WHERE job_no=?", Long.class, number);
        jdbc.update(
                "INSERT INTO slot_occupation VALUES (?,?,20,'HANDOFF',NULL,?)",
                resourceId,
                date,
                job);
        mvc.perform(
                        get("/api/v1/resources/" + resourceId + "/schedule")
                                .param("date", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.slots[20].state").value("HANDOFF"))
                .andExpect(jsonPath("data.slots[20].canBook").value(false));
        mvc.perform(update(false, 16, 44)).andExpect(status().isConflict());
    }

    @Test
    void permissionsDatesAndMissingCalendarAreChecked() throws Exception {
        mvc.perform(update(true, 16, 44).with(user(admin).roles("USER")))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE app_user SET enabled=0 WHERE id=?", adminId);
        mvc.perform(update(true, 16, 44)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/resources/" + resourceId + "/schedule").param("date", "bad"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/v1/resources/" + resourceId + "/schedule")
                                .param("date", "2026-10-20"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("该日期日历尚未生成，请稍后重试", "预约日历尚未生成"))));
        jdbc.update("UPDATE app_user SET enabled=1 WHERE id=?", adminId);
        mvc.perform(update(true, 44, 16)).andExpect(status().isBadRequest());
        mvc.perform(
                        put("/api/v1/admin/resources/" + resourceId + "/days/2026-10-14")
                                .with(user(admin).roles("ADMIN"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        "{\"revision\":0,\"enabled\":true,\"openStartSlot\":16,\"openEndSlot\":44,\"reason\":\"测试\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of(
                                                        "不能修改过去的日历",
                                                        "可申请日期为今天起七天",
                                                        "可预约日期为今天起七个业务日期"))));
    }

    @Test
    void concurrentChangesWithSameRevisionOnlyOneSucceeds() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> task =
                    () -> {
                        start.await();
                        return mvc.perform(update(true, 16, 44))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            var first = pool.submit(task);
            var second = pool.submit(task);
            start.countDown();
            assertThat(
                            new Integer[] {
                                first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)
                            })
                    .containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test
    void auditFailureRollsBackCalendarAndOutbox() throws Exception {
        doThrow(new IllegalStateException("simulated audit failure"))
                .when(audit)
                .insert(any(AdminAudit.class));
        mvc.perform(update(false, 16, 44)).andExpect(status().isInternalServerError());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revision FROM resource_day WHERE resource_id=? AND"
                                    + " book_date=?",
                                Long.class,
                                resourceId,
                                date))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT enabled FROM resource_day WHERE resource_id=? AND"
                                    + " book_date=?",
                                Boolean.class,
                                resourceId,
                                date))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",
                                Integer.class,
                                resourceId + ":" + date))
                .isZero();
    }
}
