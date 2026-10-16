package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@EnabledIfEnvironmentVariable(named = "FLASHSLOT_PRIVATE_TEST", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
class AccountProfileIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;
    String username;
    long id;
    final Instant now = Instant.parse("2026-10-16T01:00:00Z");

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(now);
        username = "profile_" + UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash,role,credit_score) VALUES (?,'private-test-secret','USER',80)", username);
        id = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }

    @Test
    void returnsCurrentAccountWithoutSecretsAndCannotSelectAnotherUser() throws Exception {
        var result = mvc.perform(get("/api/v1/me/profile").param("userId", "1")
                .with(new com.flashslot.support.TestUser(jdbc, username)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("data.id").value(id))
            .andExpect(jsonPath("data.username").value(username))
            .andExpect(jsonPath("data.creditScore").value(80))
            .andExpect(jsonPath("data.restricted").value(false))
            .andExpect(jsonPath("data.serverTime").value(now.toString()))
            .andExpect(jsonPath("data.passwordHash").doesNotExist()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-test-secret");
    }

    @Test
    void restrictionUsesCurrentDatabaseStateAndExpiresAtExactBoundary() throws Exception {
        jdbc.update("UPDATE app_user SET restriction_until=? WHERE id=?", now.plusSeconds(60).toString().replace('T', ' ').replace("Z", ""), id);
        mvc.perform(get("/api/v1/me/profile").with(new com.flashslot.support.TestUser(jdbc, username)))
            .andExpect(status().isOk()).andExpect(jsonPath("data.restricted").value(true));
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        mvc.perform(get("/api/v1/me/profile").with(new com.flashslot.support.TestUser(jdbc, username)))
            .andExpect(status().isOk()).andExpect(jsonPath("data.restricted").value(false));
    }

    @Test
    void anonymousAndDisabledAccountsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/me/profile")).andExpect(status().isUnauthorized());
        var identity = new com.flashslot.support.TestUser(jdbc, username);
        jdbc.update("UPDATE app_user SET enabled=0 WHERE id=?", id);
        mvc.perform(get("/api/v1/me/profile").with(identity)).andExpect(status().isForbidden());
    }
}
