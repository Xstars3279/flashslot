package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.resource.mapper.ScheduleReadMapper;
import com.flashslot.resource.service.ResourceScheduleService;
import com.flashslot.support.TestUser;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "FLASHSLOT_PRIVATE_REDIS_TEST", matches = "true")
class ScheduleCacheIntegrationTest {
    @Autowired ResourceScheduleService service;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean ScheduleReadMapper reads;
    long resource;
    LocalDate date = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1);

    @BeforeEach
    void setup() {
        String name = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO resource(name,location) VALUES (?,'cache')", name);
        resource = jdbc.queryForObject("SELECT id FROM resource WHERE name=?", Long.class, name);
        jdbc.update("INSERT INTO resource_day(resource_id,book_date) VALUES (?,?)", resource, date);
        clearInvocations(reads);
    }

    @Test
    void repeatedReadsHitRedisAndRevisionChangeInvalidatesImmediately() {
        service.getSchedule(resource, date);
        service.getSchedule(resource, date);
        verify(reads, times(1)).findOccupations(eq(resource), eq(date), isNull());
        jdbc.update(
                "UPDATE resource_day SET revision=revision+1 WHERE resource_id=? AND book_date=?",
                resource,
                date);
        service.getSchedule(resource, date);
        verify(reads, times(2)).findOccupations(eq(resource), eq(date), isNull());
        var keys = redis.keys("flashslot:schedule:" + resource + ":*");
        assertThat(keys).hasSize(2);
        for (String key : keys) {
            assertThat(redis.getExpire(key)).isBetween(1L, 90L);
            assertThat(redis.opsForValue().get(key)).doesNotContain("userId", "BK_", "password");
        }
    }

    @Test
    void invalidCacheJsonFallsBackToDatabase() {
        service.getSchedule(resource, date);
        String key = redis.keys("flashslot:schedule:" + resource + ":*").iterator().next();
        redis.opsForValue().set(key, "broken");
        assertThat(service.getSchedule(resource, date).getSlots()).hasSize(48);
        verify(reads, times(2)).findOccupations(eq(resource), eq(date), isNull());
    }

    @Test
    void sharedCacheNeverExposesAnotherUsersOrderAndRevokedAccountLosesOverlay() throws Exception {
        String username = "cache_" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,role) VALUES (?,'fixture','USER')",
                username);
        var result =
                mvc.perform(
                                post("/api/v1/bookings")
                                        .with(new TestUser(jdbc, username))
                                        .with(csrf())
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType("application/json")
                                        .content(
                                                "{\"resourceId\":"
                                                        + resource
                                                        + ",\"bookDate\":\""
                                                        + date
                                                        + "\",\"startSlot\":18,\"endSlot\":20,\"purpose\":\"STUDY\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String order =
                json.readTree(result.getResponse().getContentAsString())
                        .path("data")
                        .path("orderNo")
                        .asText();
        String path = "/api/v1/resources/" + resource + "/schedule?date=" + date;
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.slots[18].mine").value(false))
                .andExpect(jsonPath("data.slots[18].orderNo").isEmpty());
        mvc.perform(get(path).with(new TestUser(jdbc, username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.slots[18].orderNo").value(order));
        for (String key : redis.keys("flashslot:schedule:" + resource + ":*"))
            assertThat(redis.opsForValue().get(key)).doesNotContain(order, username);
        jdbc.update("UPDATE app_user SET enabled=0 WHERE username=?", username);
        mvc.perform(get(path).with(new TestUser(jdbc, username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.slots[18].mine").value(false))
                .andExpect(jsonPath("data.slots[18].orderNo").isEmpty());
    }
}
