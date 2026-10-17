package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.auth.mapper.TokenStateMapper;
import com.flashslot.auth.service.AccountBanCache;
import com.flashslot.auth.service.TokenStateService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@EnabledIfEnvironmentVariable(named="FLASHSLOT_PRIVATE_REDIS_TEST", matches="true")
@SpringBootTest(properties="app.account-ban-cache.enabled=true")
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RedisBanIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AccountBanCache bans;
    @Autowired TokenStateMapper states;
    @Autowired JwtDecoder decoder;
    @MockitoSpyBean PasswordEncoder passwords;
    static final String PASSWORD="Redis-ban-test-password!";
    static final String HASH=new BCryptPasswordEncoder(12).encode(PASSWORD);
    long uid;
    String name, adminToken, oldToken;

    @BeforeEach void setup() throws Exception {
        name="ban_"+UUID.randomUUID();
        uid=account(name,"USER");
        String admin="ban_admin_"+UUID.randomUUID();
        account(admin,"ADMIN");
        oldToken=login(name); adminToken=login(admin);
    }

    @Test void disableIsImmediateAndEnableRequiresFreshLogin() throws Exception {
        changeStatus(false);
        assertThat(bans.isBlocked(uid,0)).isTrue();
        mvc.perform(get("/api/v1/me/profile").header("Authorization","Bearer "+oldToken)).andExpect(status().isUnauthorized());
        clearInvocations(passwords);
        mvc.perform(loginRequest(name)).andExpect(status().isUnauthorized());
        verify(passwords,never()).matches(any(),any());
        changeStatus(true);
        mvc.perform(get("/api/v1/me/profile").header("Authorization","Bearer "+oldToken)).andExpect(status().isUnauthorized());
        String fresh=login(name);
        mvc.perform(get("/api/v1/me/profile").header("Authorization","Bearer "+fresh)).andExpect(status().isOk());
        // A delayed disable callback cannot replace the newer enabled tombstone.
        bans.synchronizeAfterCommit(uid,1,false);
        assertThat(bans.isBlocked(uid,0)).isFalse();
        assertThat(bans.isBlocked(uid,2)).isFalse();
    }

    @Test void staleBanCannotRejectANewerCredentialVersion() throws Exception {
        bans.synchronizeAfterCommit(uid,0,false);
        jdbc.update("UPDATE app_user SET credential_version=2 WHERE id=?",uid);
        String fresh=login(name);
        mvc.perform(get("/api/v1/me/profile").header("Authorization","Bearer "+fresh)).andExpect(status().isOk());
    }

    @Test void redisFailureFallsBackToEnabledAndVersionChecksInDatabase() {
        var token=decoder.decode(oldToken);
        var broken=mock(StringRedisTemplate.class);
        when(broken.opsForValue()).thenThrow(new RedisConnectionFailureException("test outage"));
        var fallback=new AccountBanCache(broken,true);
        var service=new TokenStateService(states,fallback);
        assertThat(service.valid(token)).isTrue();
        // Disabled must reject even when an external writer did not increment the version.
        jdbc.update("UPDATE app_user SET enabled=0 WHERE id=?",uid);
        assertThat(service.valid(token)).isFalse();
        verify(broken,times(1)).opsForValue();
    }

    @Test void rollbackDoesNotPublishBan() {
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        tx.executeWithoutResult(state -> {
            bans.synchronizeAfterCommit(uid,1,false);
            state.setRollbackOnly();
        });
        assertThat(bans.isBlocked(uid,0)).isFalse();
    }

    @Test void failedRedisWriteAfterCommitCannotUndoDisabledDatabaseState() {
        var broken=mock(StringRedisTemplate.class,invocation -> {
            throw new RedisConnectionFailureException("test outage");
        });
        var fallback=new AccountBanCache(broken,true);
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        tx.executeWithoutResult(state -> {
            jdbc.update("UPDATE app_user SET enabled=0,credential_version=credential_version+1 WHERE id=?",uid);
            fallback.synchronizeAfterCommit(uid,1,false);
            verifyNoInteractions(broken);
        });
        assertThat(states.version(uid)).isNull();
        assertThat(jdbc.queryForObject("SELECT credential_version FROM app_user WHERE id=?",Long.class,uid)).isEqualTo(1L);
    }

    long account(String username,String role) {
        jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?,?,?)",username,HASH,role);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?",Long.class,username);
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest(String username) throws Exception {
        return post("/api/v1/auth/login").with(r -> {r.setRemoteAddr("198.51.100."+(uid%200+1));return r;})
                .contentType("application/json").content(json.writeValueAsString(Map.of("username",username,"password",PASSWORD)));
    }
    String login(String username) throws Exception {
        var result=mvc.perform(loginRequest(username)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).at("/data/accessToken").asText();
    }
    void changeStatus(boolean enabled) throws Exception {
        mvc.perform(patch("/api/v1/admin/users/"+uid+"/status").header("Authorization","Bearer "+adminToken)
                .contentType("application/json").content(json.writeValueAsString(Map.of("enabled",enabled,"reason","Redis封禁验收"))))
                .andExpect(status().isOk());
    }
}
