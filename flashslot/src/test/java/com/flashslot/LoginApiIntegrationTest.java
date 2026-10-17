package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 登录真实校验 BCrypt，并通过真实 Bearer Token 访问安全过滤器和业务接口。 */
@EnabledIfEnvironmentVariable(named = "FLASHSLOT_PRIVATE_TEST", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
class LoginApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @MockitoBean Clock clock;
    @MockitoSpyBean com.flashslot.auth.mapper.AccountPermissionMapper accounts;
    static final AtomicInteger IP_SEQUENCE = new AtomicInteger();
    static final String PASSWORD = "test-password-OnlyForPrivateDB!";
    static final String HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);
    final Instant now = Instant.parse("2026-10-15T00:00:00Z");
    String username, admin, ip;
    long uid, adminId;

    @BeforeEach
    void fixtures() {
        when(clock.instant()).thenReturn(now);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.withZone(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(c -> Clock.fixed(now, c.getArgument(0)));
        username = "login_" + UUID.randomUUID();
        admin = "admin_" + UUID.randomUUID();
        ip = "192.0.2." + IP_SEQUENCE.incrementAndGet();
        uid = account(username, "USER");
        adminId = account(admin, "ADMIN");
    }

    @Test
    void loginReturnsTokenAndCanonicalIdentityWithoutStartingSession() throws Exception {
        var response =
                mvc.perform(login(username.toUpperCase(Locale.ROOT), PASSWORD))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("data.tokenType").value("Bearer"))
                        .andExpect(jsonPath("data.expiresAt").value("2026-10-15T02:00:00Z"))
                        .andExpect(jsonPath("data.user.username").value(username))
                        .andExpect(jsonPath("data.user.role").value("USER"))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().doesNotExist("Set-Cookie"))
                        .andReturn();
        assertThat(response.getRequest().getSession(false)).isNull();
        String body = response.getResponse().getContentAsString();
        assertThat(body).doesNotContain(PASSWORD, HASH, "passwordHash");
        String token = json.readTree(body).at("/data/accessToken").asText();
        assertThat(token.split("\\.")).hasSize(3);
        assertThat(SignedJWT.parse(token).getJWTClaimsSet().getLongClaim("userId")).isEqualTo(uid);
        mvc.perform(get("/api/v1/me/bookings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items").isEmpty());
        mvc.perform(get("/api/v1/me/bookings")).andExpect(status().isUnauthorized());
    }

    @Test
    void realTokensConnectAdminResourceCreationUserBookingAndOrderQueries() throws Exception {
        String adminToken = token(admin), userToken = token(username);
        org.mockito.Mockito.clearInvocations(accounts);
        String body =
                "{\"name\":\"token-device\",\"location\":\"A201\",\"defaultOpenStartSlot\":16,\"defaultOpenEndSlot\":44,\"reason\":\"联调\"}";
        mvc.perform(
                        post("/api/v1/admin/resources")
                                .header("Authorization", "Bearer " + userToken)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isForbidden());
        var created =
                mvc.perform(
                                post("/api/v1/admin/resources")
                                        .header("Authorization", "Bearer " + adminToken)
                                        .contentType("application/json")
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn();
        long resource =
                json.readTree(created.getResponse().getContentAsString()).at("/data/id").asLong();
        var booked =
                mvc.perform(
                                post("/api/v1/bookings")
                                        .header("Authorization", "Bearer " + userToken)
                                        .header("Idempotency-Key", "real-token")
                                        .contentType("application/json")
                                        .content(
                                                "{\"resourceId\":"
                                                        + resource
                                                        + ",\"bookDate\":\"2026-10-16\",\"startSlot\":18,\"endSlot\":20,\"purpose\":\"STUDY\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String order =
                json.readTree(booked.getResponse().getContentAsString())
                        .at("/data/orderNo")
                        .asText();
        mvc.perform(get("/api/v1/bookings/" + order).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.orderNo").value(order));
        mvc.perform(get("/api/v1/me/bookings").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items[0].orderNo").value(order));
        // 用户名变化甚至被其他账号复用，都不能改变已签发 Token 的用户 ID 归属。
        jdbc.update("UPDATE app_user SET username=? WHERE id=?", username + "_renamed", uid);
        account(username, "USER");
        mvc.perform(get("/api/v1/bookings/" + order).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.orderNo").value(order));
        jdbc.update("UPDATE app_user SET role='USER' WHERE id=?", adminId);
        mvc.perform(
                        post("/api/v1/admin/resources")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verify(accounts, org.mockito.Mockito.never())
                .findCurrentAccount(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void wrongUnknownDisabledAndDemoCredentialsShareOneFailureResponse() throws Exception {
        JsonNode wrong = failure(username, "wrong-password");
        assertThat(failure("unknown_" + UUID.randomUUID(), PASSWORD)).isEqualTo(wrong);
        jdbc.update("UPDATE app_user SET enabled=0 WHERE id=?", uid);
        assertThat(failure(username, PASSWORD)).isEqualTo(wrong);
        jdbc.update("UPDATE app_user SET enabled=1,role='DEMO' WHERE id=?", uid);
        assertThat(failure(username, PASSWORD)).isEqualTo(wrong);
        assertThat(wrong.get("code").asInt()).isZero();
        assertThat(wrong.get("msg").asText()).isEqualTo("用户名或密码错误");
    }

    @Test
    void disabledAccountIsRejectedEvenWithPreviouslyIssuedToken() throws Exception {
        String token = token(username);
        jdbc.update("UPDATE app_user SET enabled=0 WHERE id=?", uid);
        mvc.perform(get("/api/v1/me/bookings").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(org.hamcrest.Matchers.isIn(java.util.List.of("请先登录"))));
    }

    @Test
    void malformedOrExpiredOrForeignTokensCannotAuthenticate() throws Exception {
        String good = token(username);
        int signatureStart = good.lastIndexOf('.') + 1;
        String tampered =
                good.substring(0, signatureStart)
                        + (good.charAt(signatureStart) == 'A' ? 'B' : 'A')
                        + good.substring(signatureStart + 1);
        var claims = claims();
        for (String invalid :
                List.of(
                        "not-a-jwt",
                        tampered,
                        signed(claims.expirationTime(Date.from(now)).build()),
                        signed(claims().issuer("other-app").build()),
                        signed(claims().audience("other-api").build()),
                        signed(claims().issueTime(Date.from(now.plusSeconds(60))).build()),
                        signed(claims().expirationTime(null).build()))) {
            mvc.perform(get("/api/v1/me/bookings").header("Authorization", "Bearer " + invalid))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("code").value(0))
                    .andExpect(
                            jsonPath("msg")
                                    .value(
                                            org.hamcrest.Matchers.isIn(
                                                    java.util.List.of("用户名或密码错误", "请先登录"))));
        }
        mvc.perform(get("/api/v1/me/bookings").header("Authorization", "Bearer " + good))
                .andExpect(status().isOk());
        when(clock.instant()).thenReturn(now.plusSeconds(7200));
        mvc.perform(get("/api/v1/me/bookings").header("Authorization", "Bearer " + good))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void queryCookiesAndOldHttpSessionAreNotAuthenticationSources() throws Exception {
        String token = token(username);
        mvc.perform(get("/api/v1/me/bookings").param("access_token", token))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/api/v1/me/bookings")
                                .cookie(new jakarta.servlet.http.Cookie("accessToken", token)))
                .andExpect(status().isUnauthorized());
        var session = new MockHttpSession();
        session.setAttribute(
                "SPRING_SECURITY_CONTEXT",
                new SecurityContextImpl(
                        new UsernamePasswordAuthenticationToken(
                                username,
                                "unused",
                                List.of(new SimpleGrantedAuthority("ROLE_USER")))));
        mvc.perform(get("/api/v1/me/bookings").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidPayloadAndPasswordsBeyondBcryptByteLimit() throws Exception {
        for (String body :
                List.of(
                        "{}",
                        "{\"username\":\"x\",\"password\":\"\"}",
                        json.writeValueAsString(
                                Map.of("username", username, "password", "中".repeat(25))),
                        json.writeValueAsString(
                                Map.of(
                                        "username",
                                        username,
                                        "password",
                                        PASSWORD,
                                        "role",
                                        "ADMIN")))) {
            mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(body))
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
        }
    }

    @Test
    void repeatedAttemptsAreLimitedAcrossIpsAndWindowExpires() throws Exception {
        boolean realRedis="true".equals(System.getenv("FLASHSLOT_PRIVATE_REDIS_TEST"));
        var before=realRedis?new HashSet<>(redis.keys("flashslot:rate:login:*")):Set.<String>of();
        for (int i = 0; i < 5; i++) {
            mvc.perform(
                            login(username, "wrong")
                                    .with(
                                            request -> {
                                                request.setRemoteAddr(
                                                        "198.51.100."
                                                                + IP_SEQUENCE.incrementAndGet());
                                                return request;
                                            }))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(login(username, PASSWORD))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of(
                                                        "登录尝试过于频繁，请稍后重试", "登录请求过于频繁，请稍后重试"))))
                .andExpect(header().exists("Retry-After"));
        when(clock.instant()).thenReturn(now.plusSeconds(61));
        if(realRedis) {
            // Redis TTL follows server time, not the application's mocked business Clock.
            mvc.perform(login(username,PASSWORD)).andExpect(status().isTooManyRequests());
            var created=new HashSet<>(redis.keys("flashslot:rate:login:*")); created.removeAll(before);
            for(String key:created) redis.expire(key,Duration.ofMillis(10));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2))
                .until(() -> created.stream().noneMatch(key -> Boolean.TRUE.equals(redis.hasKey(key))));
        }
        mvc.perform(login(username, PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void collationEquivalentNamesShareTheSameAccountLimit() throws Exception {
        String accented = username.replace("login_", "lógin_");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT id FROM app_user WHERE username=?", Long.class, accented))
                .isEqualTo(uid);
        for (int i = 0; i < 5; i++) {
            mvc.perform(
                            login(username, "wrong")
                                    .with(
                                            request -> {
                                                request.setRemoteAddr(
                                                        "198.51.100."
                                                                + IP_SEQUENCE.incrementAndGet());
                                                return request;
                                            }))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(
                        login(accented, PASSWORD)
                                .with(
                                        request -> {
                                            request.setRemoteAddr(
                                                    "198.51.100." + IP_SEQUENCE.incrementAndGet());
                                            return request;
                                        }))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of(
                                                        "登录尝试过于频繁，请稍后重试", "登录请求过于频繁，请稍后重试"))));
    }

    @Test
    void authenticationDatabaseFailureIsNotReportedAsWrongPassword() throws Exception {
        org.mockito.Mockito.doThrow(
                        new org.springframework.dao.DataAccessResourceFailureException(
                                "injected database outage"))
                .when(accounts)
                .findForLogin(username);
        mvc.perform(login(username, PASSWORD))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("code").value(0))
                .andExpect(
                        jsonPath("msg")
                                .value(
                                        org.hamcrest.Matchers.isIn(
                                                java.util.List.of("登录服务暂时不可用，请稍后重试"))));
    }

    @Test
    void signedTokenWithoutPositiveIntegerUserIdCannotAuthenticate() throws Exception {
        for (Object invalidId : new Object[] {null, 0L, -1L, "1", 1.5}) {
            String invalid = signed(claims().claim("userId", invalidId).build());
            mvc.perform(get("/api/v1/me/bookings").header("Authorization", "Bearer " + invalid))
                    .andExpect(status().isUnauthorized());
        }
    }

    private long account(String name, String role) {
        jdbc.update(
                "INSERT INTO app_user(username,password_hash,role) VALUES (?,?,?)",
                name,
                HASH,
                role);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, name);
    }

    private MockHttpServletRequestBuilder login(String name, String password) throws Exception {
        return post("/api/v1/auth/login")
                .with(
                        request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", name, "password", password)));
    }

    private String token(String name) throws Exception {
        var response = mvc.perform(login(name, PASSWORD)).andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString())
                .at("/data/accessToken")
                .asText();
    }

    private JsonNode failure(String name, String password) throws Exception {
        var response =
                mvc.perform(login(name, password)).andExpect(status().isUnauthorized()).andReturn();
        return json.readTree(response.getResponse().getContentAsString());
    }

    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .subject(username)
                .issuer("flashslot")
                .audience("flashslot-api")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(7200)))
                .claim("userId", uid)
                .claim("roles", List.of("USER"));
    }

    /** 构造签名正确但声明无效的 Token，验证解码器没有仅验签名而漏掉时间和用途。 */
    private String signed(JWTClaimsSet claims) throws Exception {
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        token.sign(new MACSigner(Base64.getDecoder().decode(System.getenv("JWT_SECRET"))));
        return token.serialize();
    }
}
