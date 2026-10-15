package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import java.util.concurrent.*;

@EnabledIfEnvironmentVariable(named = "FLASHSLOT_PRIVATE_TEST", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
class ResourceApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean AdminAuditMapper audit;
    String admin;

    private com.flashslot.support.TestUser user(String name) {
        return new com.flashslot.support.TestUser(jdbc, name);
    }

    @BeforeEach
    void account() {
        admin = "admin_" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,role) VALUES (?,'test-only','ADMIN')",
                admin);
    }

    MockHttpServletRequestBuilder createRequest() {
        return post("/api/v1/admin/resources")
                .with(user(admin).roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                        """
{"name":"打印机01","location":"A201","defaultOpenStartSlot":16,"defaultOpenEndSlot":44,"reason":"新增设备"}
""");
    }

    long createResource() throws Exception {
        var result =
                mvc.perform(createRequest())
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("code").value(1))
                        .andExpect(jsonPath("msg").isEmpty())
                        .andExpect(header().doesNotExist("ETag"))
                        .andExpect(jsonPath("data.version").value(0))
                        .andReturn();
        return json.readTree(result.getResponse().getContentAsString())
                .get("data")
                .get("id")
                .asLong();
    }

    @Test
    void createGeneratesCalendarsAuditAndReadableDetail() throws Exception {
        long id = createResource();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM resource_day WHERE resource_id=?",
                                Integer.class,
                                id))
                .isEqualTo(14);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM admin_audit WHERE operation='RESOURCE_CREATE'"
                                    + " AND target_id=?",
                                Integer.class,
                                "" + id))
                .isEqualTo(1);
        mvc.perform(get("/api/v1/resources/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.name").value("打印机01"));
        mvc.perform(get("/api/v1/resources").param("cursor", "" + (id - 1)).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items[0].id").value(id));
    }

    @Test
    void validationAndMissingResourceHaveStableErrors() throws Exception {
        mvc.perform(
                        createRequest()
                                .content(
                                        "{\"name\":\""
                                            + " \",\"location\":\"A\",\"defaultOpenStartSlot\":44,\"defaultOpenEndSlot\":16,\"reason\":\"新增\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of(
                                                        "分页参数不合法",
                                                        "幂等键格式不合法",
                                                        "幂等键须为 1 至 64 位字母、数字或 ._:-",
                                                        "请求参数不合法，请检查字段、格式和范围"))));
        mvc.perform(
                        createRequest()
                                .content(
                                        "{\"name\":\"A\",\"location\":\"A\",\"defaultOpenStartSlot\":44,\"defaultOpenEndSlot\":16,\"reason\":\"新增\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of(
                                                        "开放时间需满足 0 <= 起始格 < 结束格 <= 48",
                                                        "起始时间格必须小于结束时间格"))));
        mvc.perform(get("/api/v1/resources/9223372036854775807"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(org.hamcrest.Matchers.isIn(java.util.List.of("设备不存在"))))
                .andExpect(jsonPath("msg").isNotEmpty())
                .andExpect(jsonPath("data").isEmpty());
        mvc.perform(get("/api/v1/resources").param("limit", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/resources").param("cursor", "abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminPermissionsRecheckDatabaseState() throws Exception {
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM resource", Integer.class);
        mvc.perform(post("/api/v1/admin/resources").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("用户名或密码错误", "请先登录"))))
                .andExpect(jsonPath("data").isEmpty());
        mvc.perform(createRequest().with(user(admin).roles("USER")))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE app_user SET enabled=0 WHERE username=?", admin);
        mvc.perform(createRequest())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(org.hamcrest.Matchers.isIn(java.util.List.of("账号已停用"))));
        jdbc.update("UPDATE app_user SET enabled=1,role='USER' WHERE username=?", admin);
        mvc.perform(createRequest())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of(
                                                        "当前账号不是管理员",
                                                        "无权执行此操作",
                                                        "演示账号不能预约正式设备",
                                                        "需要管理员权限"))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource", Integer.class))
                .isEqualTo(before);
    }

    MockHttpServletRequestBuilder patchRequest(long id) {
        return patch("/api/v1/admin/resources/" + id)
                .with(user(admin).roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(
                        "{\"version\":0,\"name\":\"新名称\",\"defaultOpenStartSlot\":18,\"reason\":\"调整默认配置\"}");
    }

    @Test
    void patchChecksVersionAndPreservesExistingCalendar() throws Exception {
        long id = createResource();
        mvc.perform(patchRequest(id))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("data.version").value(1))
                .andExpect(jsonPath("data.defaultOpenStartSlot").value(18));
        mvc.perform(patchRequest(id))
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
        assertThat(
                        jdbc.queryForObject(
                                "SELECT MIN(open_start_slot) FROM resource_day WHERE resource_id=?",
                                Integer.class,
                                id))
                .isEqualTo(16);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM admin_audit WHERE operation='RESOURCE_UPDATE'"
                                    + " AND target_id=?",
                                Integer.class,
                                "" + id))
                .isEqualTo(1);
        mvc.perform(patchRequest(id).content("{\"version\":-1,\"name\":\"A\",\"reason\":\"修改\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentUpdatesWithSameVersionOnlyOneSucceeds() throws Exception {
        long id = createResource();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> update =
                    () -> {
                        start.await();
                        return mvc.perform(patchRequest(id)).andReturn().getResponse().getStatus();
                    };
            var first = pool.submit(update);
            var second = pool.submit(update);
            start.countDown();
            assertThat(
                            new Integer[] {
                                first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)
                            })
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT version FROM resource WHERE id=?", Long.class, id))
                .isEqualTo(1L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM admin_audit WHERE operation='RESOURCE_UPDATE'"
                                    + " AND target_id=?",
                                Integer.class,
                                "" + id))
                .isEqualTo(1);
    }

    @Test
    void paginationContinuesWithoutRepeatingLastItem() throws Exception {
        long first = createResource(), second = createResource();
        var page =
                mvc.perform(
                                get("/api/v1/resources")
                                        .param("cursor", "" + (first - 1))
                                        .param("limit", "1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("data.items[0].id").value(first))
                        .andExpect(jsonPath("data.hasMore").value(true))
                        .andReturn();
        String cursor =
                json.readTree(page.getResponse().getContentAsString())
                        .get("data")
                        .get("nextCursor")
                        .asText();
        mvc.perform(get("/api/v1/resources").param("cursor", cursor).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items[0].id").value(second))
                .andExpect(jsonPath("data.hasMore").value(false))
                .andExpect(jsonPath("data.nextCursor").isEmpty());
    }

    @Test
    void unauthorizedUpdateRollsBackAndUnsupportedStatusIsRejected() throws Exception {
        long id = createResource();
        jdbc.update("UPDATE app_user SET enabled=0 WHERE username=?", admin);
        mvc.perform(patchRequest(id)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT version FROM resource WHERE id=?", Long.class, id))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT name FROM resource WHERE id=?", String.class, id))
                .isEqualTo("打印机01");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM admin_audit WHERE operation='RESOURCE_UPDATE'"
                                    + " AND target_id=?",
                                Integer.class,
                                "" + id))
                .isZero();
        jdbc.update("UPDATE app_user SET enabled=1 WHERE username=?", admin);
        mvc.perform(patchRequest(id).content("{\"status\":\"RETIRED\",\"reason\":\"退役\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        patch("/api/v1/admin/resources/" + id)
                                .with(user(admin).roles("ADMIN"))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"name\":\"A\",\"reason\":\"修改\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unexpectedFailureReturnsResultAndRollsBackEntireCreation() throws Exception {
        int resources = jdbc.queryForObject("SELECT COUNT(*) FROM resource", Integer.class);
        int calendars = jdbc.queryForObject("SELECT COUNT(*) FROM resource_day", Integer.class);
        doThrow(new IllegalStateException("internal audit failure details"))
                .when(audit)
                .insert(any(AdminAudit.class));
        mvc.perform(createRequest())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("服务暂时不可用，请稍后重试"))))
                .andExpect(jsonPath("msg").value("服务暂时不可用，请稍后重试"))
                .andExpect(jsonPath("data").isEmpty());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource", Integer.class))
                .isEqualTo(resources);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_day", Integer.class))
                .isEqualTo(calendars);
    }
}
