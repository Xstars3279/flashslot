package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.flashslot.support.TestUser;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;

import java.time.*;
import java.util.UUID;

/** 三个接口使用真实 MySQL 验证归属、管理员权限、游标和重复已读。 */
@EnabledIfEnvironmentVariable(named = "FLASHSLOT_PRIVATE_TEST", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
class OperationsNotificationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean Clock clock;
    String owner, other, admin;
    long ownerId, otherId, adminId;
    final Instant now = Instant.parse("2026-10-15T02:00:00Z");

    @BeforeEach
    void fixtures() {
        when(clock.instant()).thenReturn(now);
        owner = "note_" + UUID.randomUUID();
        other = "other_" + UUID.randomUUID();
        admin = "audit_" + UUID.randomUUID();
        ownerId = account(owner, "USER");
        otherId = account(other, "USER");
        adminId = account(admin, "ADMIN");
    }

    private long account(String name, String role) {
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,role) VALUES (?,'fixture',?)",
                name,
                role);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, name);
    }

    private TestUser user(String who) {
        return new TestUser(jdbc, who);
    }

    private long notification(long user, String title, boolean read) {
        String event = UUID.randomUUID().toString();
        String payload =
                json.createObjectNode()
                        .put("schemaVersion", 1)
                        .put("title", title)
                        .put("content", "预约成功，请按时签到")
                        .put("orderNo", "BK_" + UUID.randomUUID())
                        .put("privateToken", "must-not-leak")
                        .toString();
        jdbc.update(
                "INSERT INTO notification(user_id,event_id,type,payload,read_at,created_at) VALUES"
                    + " (?,?,'BOOKING_CREATED',?,?,'2026-10-15 02:00:00')",
                user,
                event,
                payload,
                read ? java.sql.Timestamp.from(now.minusSeconds(1)) : null);
        return jdbc.queryForObject(
                "SELECT id FROM notification WHERE event_id=?", Long.class, event);
    }

    private long audit(String target, String operation) {
        jdbc.update(
                "INSERT INTO admin_audit(actor_id,operation,target_id,reason,created_at) VALUES"
                    + " (?,?,?,'配置测试','2026-10-15 02:00:00')",
                adminId,
                operation,
                target);
        return jdbc.queryForObject(
                "SELECT id FROM admin_audit WHERE actor_id=? AND target_id=?",
                Long.class,
                adminId,
                target);
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("data");
    }

    @Test
    void notificationListIsPrivateAndReturnsUnreadCountWithoutRawPayload() throws Exception {
        long mine = notification(ownerId, "预约成功", false);
        notification(otherId, "别人的消息", false);
        mvc.perform(
                        get("/api/v1/me/notifications")
                                .with(user(owner))
                                .param("userId", Long.toString(otherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("code").value(1))
                .andExpect(jsonPath("data.items.length()").value(1))
                .andExpect(jsonPath("data.items[0].id").value(mine))
                .andExpect(jsonPath("data.items[0].title").value("预约成功"))
                .andExpect(jsonPath("data.items[0].userId").doesNotExist())
                .andExpect(jsonPath("data.items[0].eventId").doesNotExist())
                .andExpect(jsonPath("data.items[0].payload").doesNotExist())
                .andExpect(jsonPath("data.unreadCount").value(1));
    }

    @Test
    void notificationCursorHandlesSameMillisecondAndNewInsert() throws Exception {
        long first = notification(ownerId, "first", false),
                second = notification(ownerId, "second", false);
        var page =
                data(
                        mvc.perform(
                                        get("/api/v1/me/notifications")
                                                .with(user(owner))
                                                .param("limit", "1"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("data.hasMore").value(true))
                                .andReturn());
        assertThat(page.get("items").get(0).get("id").asLong()).isEqualTo(second);
        notification(ownerId, "new", false);
        mvc.perform(
                        get("/api/v1/me/notifications")
                                .with(user(owner))
                                .param("limit", "1")
                                .param("cursor", page.get("nextCursor").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items[0].id").value(first))
                .andExpect(jsonPath("data.hasMore").value(false));
        long foreign = notification(otherId, "foreign", false);
        mvc.perform(
                        get("/api/v1/me/notifications")
                                .with(user(owner))
                                .param("cursor", Long.toString(foreign)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unreadFilterAndRepeatedReadPreserveFirstReadTime() throws Exception {
        long unread = notification(ownerId, "unread", false);
        notification(ownerId, "read", true);
        mvc.perform(get("/api/v1/me/notifications").with(user(owner)).param("unreadOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items.length()").value(1));
        mvc.perform(
                        post("/api/v1/me/notifications/" + unread + "/read")
                                .with(user(owner))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.readAt").value(now.toString()));
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        mvc.perform(
                        post("/api/v1/me/notifications/" + unread + "/read")
                                .with(user(owner))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.readAt").value(now.toString()));
        mvc.perform(get("/api/v1/me/notifications").with(user(owner)).param("unreadOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items.length()").value(0))
                .andExpect(jsonPath("data.unreadCount").value(0));
    }

    @Test
    void readCannotTouchForeignNotificationAndMissingAlsoReturns404() throws Exception {
        long id = notification(otherId, "foreign", false);
        mvc.perform(post("/api/v1/me/notifications/" + id + "/read").with(user(owner)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/v1/me/notifications/9223372036854775807/read")
                                .with(user(owner))
                                .with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT read_at FROM notification WHERE id=?",
                                java.sql.Timestamp.class,
                                id))
                .isNull();
    }

    @Test
    void accountStateAndAuthenticationAreRequiredForNotifications() throws Exception {
        long id = notification(ownerId, "private", false);
        mvc.perform(get("/api/v1/me/notifications")).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE app_user SET enabled=0 WHERE id=?", ownerId);
        mvc.perform(get("/api/v1/me/notifications").with(user(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/me/notifications/" + id + "/read").with(user(owner)).with(csrf()))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE app_user SET enabled=1,role='DEMO' WHERE id=?", ownerId);
        mvc.perform(get("/api/v1/me/notifications").with(user(owner)))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditListSupportsFiltersAndSameMillisecondPagination() throws Exception {
        long first = audit("first_" + UUID.randomUUID(), "RESOURCE_UPDATE");
        long second = audit("second_" + UUID.randomUUID(), "RESOURCE_UPDATE");
        audit("third_" + UUID.randomUUID(), "BOOKING_CANCEL");
        var request =
                get("/api/v1/admin/audits")
                        .with(user(admin).roles("ADMIN"))
                        .param("actorId", Long.toString(adminId))
                        .param("operation", "RESOURCE_UPDATE")
                        .param("limit", "1");
        var page =
                data(
                        mvc.perform(request)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("data.items[0].id").value(second))
                                .andExpect(jsonPath("data.items[0].actorUsername").value(admin))
                                .andExpect(jsonPath("data.items[0].passwordHash").doesNotExist())
                                .andReturn());
        mvc.perform(
                        get("/api/v1/admin/audits")
                                .with(user(admin).roles("ADMIN"))
                                .param("actorId", Long.toString(adminId))
                                .param("operation", "RESOURCE_UPDATE")
                                .param("limit", "1")
                                .param("cursor", page.get("nextCursor").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items[0].id").value(first))
                .andExpect(jsonPath("data.hasMore").value(false));
        mvc.perform(
                        get("/api/v1/admin/audits")
                                .with(user(admin).roles("ADMIN"))
                                .param("actorId", Long.toString(adminId))
                                .param("from", "2026-10-16T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items.length()").value(0));
    }

    @Test
    void auditsRequireBothTokenAndCurrentDatabaseAdminRole() throws Exception {
        audit("secret_" + UUID.randomUUID(), "RESOURCE_UPDATE");
        mvc.perform(get("/api/v1/admin/audits")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/audits").with(user(owner)))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE app_user SET role='USER' WHERE id=?", adminId);
        mvc.perform(get("/api/v1/admin/audits").with(user(admin).roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidPaginationAndTimeRangeAreRejected() throws Exception {
        mvc.perform(get("/api/v1/me/notifications").with(user(owner)).param("limit", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/notifications").with(user(owner)).param("cursor", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/v1/admin/audits")
                                .with(user(admin).roles("ADMIN"))
                                .param("from", "2026-10-16T00:00:00Z")
                                .param("to", "2026-10-15T00:00:00Z"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/v1/admin/audits")
                                .with(user(admin).roles("ADMIN"))
                                .param("cursor", "9223372036854775807"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentFirstReadsKeepTheFirstTimestamp() throws Exception {
        long id = notification(ownerId, "concurrent", false);
        when(clock.instant()).thenReturn(now, now.plusSeconds(60));
        var gate = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<JsonNode> read =
                () -> {
                    gate.await();
                    return data(
                            mvc.perform(
                                            post("/api/v1/me/notifications/" + id + "/read")
                                                    .with(user(owner))
                                                    .with(csrf()))
                                    .andExpect(status().isOk())
                                    .andReturn());
                };
        try {
            var first = pool.submit(read);
            var second = pool.submit(read);
            gate.countDown();
            assertThat(first.get(15, java.util.concurrent.TimeUnit.SECONDS).get("readAt").asText())
                    .isEqualTo(now.toString());
            assertThat(second.get(15, java.util.concurrent.TimeUnit.SECONDS).get("readAt").asText())
                    .isEqualTo(now.toString());
        } finally {
            pool.shutdownNow();
        }
    }
}
