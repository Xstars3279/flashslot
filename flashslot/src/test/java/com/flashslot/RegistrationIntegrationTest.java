package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties="app.registration.development-code-enabled=true")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="FLASHSLOT_PRIVATE_REDIS_TEST",matches="true")
class RegistrationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.flashslot.auth.service.LoginService logins;
    String phone;
    static final String PASSWORD="Registration-test-password!";
    @BeforeEach void setup() { phone="139"+String.format("%08d",ThreadLocalRandom.current().nextInt(100000000)); }
    String code() throws Exception {
        var result=mvc.perform(post("/api/v1/auth/registration/code").contentType("application/json")
            .content(json.writeValueAsString(Map.of("phone",phone))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        String code=json.readTree(result.getResponse().getContentAsString()).at("/data/developmentCode").asText();
        assertThat(code).matches("[0-9]{6}");
        return code;
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(String code) throws Exception {
        return post("/api/v1/auth/register").contentType("application/json")
            .content(json.writeValueAsString(Map.of("phone",phone,"code",code,"password",PASSWORD)));
    }
    @Test void registrationAutomaticallyLogsInAsUserAndPhonePasswordCanLoginAgain() throws Exception {
        String code=code();
        String key="flashslot:registration:code:"+phone;
        assertThat(redis.getExpire(key)).isBetween(1L,300L);
        assertThat(redis.opsForValue().get(key)).isNotEqualTo(code);
        var result=mvc.perform(register(code)).andExpect(status().isCreated()).andExpect(jsonPath("data.user.role").value("USER"))
            .andExpect(jsonPath("data.user.username").value(phone)).andReturn();
        String token=json.readTree(result.getResponse().getContentAsString()).at("/data/accessToken").asText();
        mvc.perform(get("/api/v1/me/profile").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/users").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        assertThat(redis.hasKey(key)).isFalse();
        mvc.perform(register(code)).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
            .content(json.writeValueAsString(Map.of("username",phone,"password",PASSWORD)))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE phone=? AND role='USER'",Integer.class,phone)).isEqualTo(1);
    }
    @Test void cooldownWrongAttemptsAndExpiryAreEnforcedByRedis() throws Exception {
        String code=code();
        mvc.perform(post("/api/v1/auth/registration/code").contentType("application/json").content(json.writeValueAsString(Map.of("phone",phone))))
            .andExpect(status().isTooManyRequests());
        String wrong=code.equals("000000")?"111111":"000000";
        for(int i=0;i<5;i++) mvc.perform(register(wrong)).andExpect(status().isBadRequest());
        mvc.perform(register(code)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username=?",Integer.class,phone)).isZero();
        phone="138"+String.format("%08d",ThreadLocalRandom.current().nextInt(100000000));
        String expired=code();
        redis.delete("flashslot:registration:code:"+phone);
        mvc.perform(register(expired)).andExpect(status().isBadRequest());
    }
    @Test void clientCannotRequestAdminRoleOrUseInvalidPhone() throws Exception {
        String code=code();
        mvc.perform(post("/api/v1/auth/register").contentType("application/json")
            .content(json.writeValueAsString(Map.of("phone",phone,"code",code,"password",PASSWORD,"role","ADMIN"))))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/registration/code").contentType("application/json").content("{\"phone\":\"123\"}"))
            .andExpect(status().isBadRequest());
    }
    @Test void concurrentRegistrationCreatesOnlyOneAccount() throws Exception {
        String code=code();
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            Callable<Integer> submit=() -> { start.await(); return mvc.perform(register(code)).andReturn().getResponse().getStatus(); };
            var first=pool.submit(submit); var second=pool.submit(submit); start.countDown();
            assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE phone=?",Integer.class,phone)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void databaseRollbackPreservesCodeForRetry() throws Exception {
        String code=code();
        org.mockito.Mockito.doThrow(new com.flashslot.common.web.BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"INJECTED","测试回滚"))
            .doCallRealMethod().when(logins).login(org.mockito.ArgumentMatchers.any());
        mvc.perform(register(code)).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE phone=?",Integer.class,phone)).isZero();
        assertThat(redis.hasKey("flashslot:registration:code:"+phone)).isTrue();
        mvc.perform(register(code)).andExpect(status().isCreated());
    }
}
