package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.auth.mapper.TokenStateMapper;
import com.flashslot.auth.service.TokenStateService;
import com.flashslot.operations.mapper.AdminAuditMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="FLASHSLOT_PRIVATE_TEST",matches="true")
class AccountRevocationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired TokenStateMapper states;
    @Autowired TokenStateService tokens;
    @Autowired JwtDecoder decoder;
    @MockitoSpyBean PasswordEncoder passwords;
    @MockitoSpyBean AdminAuditMapper audit;
    static final String PASSWORD="account-revocation-test-password!";
    static final String HASH=new BCryptPasswordEncoder(12).encode(PASSWORD);
    String name,oldToken,adminToken;
    long uid;
    @BeforeEach void setup() throws Exception {
        name="revoke_"+UUID.randomUUID(); uid=account(name,"USER");
        String admin="revoke_admin_"+UUID.randomUUID(); account(admin,"ADMIN");
        oldToken=login(name); adminToken=login(admin);
    }
    @Test void disableRejectsImmediatelyAndEnableDoesNotReviveOldTokens() throws Exception {
        changeStatus(false,200);
        mvc.perform(get("/api/v1/me/profile").header("Authorization","Bearer "+oldToken)).andExpect(status().isUnauthorized());
        clearInvocations(passwords);
        mvc.perform(loginRequest(name)).andExpect(status().isUnauthorized());
        verify(passwords,never()).matches(any(),any());
        changeStatus(true,200);
        mvc.perform(get("/api/v1/me/profile").header("Authorization","Bearer "+oldToken)).andExpect(status().isUnauthorized());
        String fresh=login(name);
        mvc.perform(get("/api/v1/me/profile").header("Authorization","Bearer "+fresh)).andExpect(status().isOk());
        assertThat(states.version(uid)).isEqualTo(2L);
    }
    @Test void persistentEnabledFlagAndRevocationAreAuthoritative() {
        var token=decoder.decode(oldToken);
        assertThat(tokens.valid(token)).isTrue();
        jdbc.update("UPDATE app_user SET enabled=0 WHERE id=?",uid);
        assertThat(tokens.valid(token)).isFalse();
        jdbc.update("UPDATE app_user SET enabled=1 WHERE id=?",uid);
        tokens.revoke(token);
        assertThat(tokens.valid(token)).isFalse();
    }
    @Test void auditFailureRollsBackDisableAndCredentialVersionTogether() throws Exception {
        doThrow(new IllegalStateException("audit down")).when(audit).insert(any(com.flashslot.operations.entity.AdminAudit.class));
        changeStatus(false,500);
        assertThat(states.version(uid)).isEqualTo(0L);
        mvc.perform(get("/api/v1/me/profile").header("Authorization","Bearer "+oldToken)).andExpect(status().isOk());
    }
    long account(String username,String role) {
        jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?,?,?)",username,HASH,role);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?",Long.class,username);
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest(String username) throws Exception {
        return post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("198.51.100."+(uid%200+1)); return r; })
            .contentType("application/json").content(json.writeValueAsString(Map.of("username",username,"password",PASSWORD)));
    }
    String login(String username) throws Exception {
        return json.readTree(mvc.perform(loginRequest(username)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/accessToken").asText();
    }
    void changeStatus(boolean enabled,int expected) throws Exception {
        mvc.perform(patch("/api/v1/admin/users/"+uid+"/status").header("Authorization","Bearer "+adminToken)
            .contentType("application/json").content(json.writeValueAsString(Map.of("enabled",enabled,"reason","persistent account state"))))
            .andExpect(status().is(expected));
    }
}
