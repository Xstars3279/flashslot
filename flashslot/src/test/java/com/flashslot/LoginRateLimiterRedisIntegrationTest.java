package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashslot.auth.service.LoginRateLimiter;
import java.util.*;
import java.util.concurrent.*;
import java.time.Duration;
import static org.awaitility.Awaitility.await;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

@EnabledIfEnvironmentVariable(named="FLASHSLOT_PRIVATE_REDIS_TEST", matches="true")
@SpringBootTest(properties="app.security.trusted-proxies=192.0.2.254/32")
@AutoConfigureMockMvc
class LoginRateLimiterRedisIntegrationTest {
    @Autowired ApplicationContext context;
    @Autowired StringRedisTemplate redis;
    @Autowired MockMvc mvc;

    LoginRateLimiter instance() {
        return context.getAutowireCapableBeanFactory().createBean(LoginRateLimiter.class);
    }

    @Test void concurrentInstancesShareOneAtomicAccountBudget() throws Exception {
        var first=instance(); var second=instance();
        String identity="shared-"+UUID.randomUUID();
        var gate=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(10);
        try {
            var calls=new ArrayList<Future<Long>>();
            for(int i=0;i<20;i++) {
                var limiter=i%2==0?first:second;
                calls.add(pool.submit(() -> { gate.await(); return limiter.tryAcquireAccount(null,identity); }));
            }
            gate.countDown();
            int allowed=0;
            for(var call:calls) if(call.get(10,TimeUnit.SECONDS)==0) allowed++;
            assertThat(allowed).isEqualTo(5);
        } finally { pool.shutdownNow(); }
    }

    @Test void trustedProxyClientsHaveIndependentIpBudgets() throws Exception {
        String name="missing-"+UUID.randomUUID();
        // Isolate IP gating without repeatedly running expensive password verification.
        var limiter=instance();
        for(int i=0;i<30;i++) assertThat(limiter.tryAcquireIp("198.51.100.201")).isZero();
        mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("192.0.2.254"); return r; })
            .header("X-Real-IP","198.51.100.201").contentType("application/json")
            .content("{\"username\":\""+name+"\",\"password\":\"wrong-password\"}"))
            .andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr("192.0.2.254"); return r; })
            .header("X-Real-IP","198.51.100.202").contentType("application/json")
            .content("{\"username\":\""+name+"\",\"password\":\"wrong-password\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test void redisExpiryReopensWindowWithoutAnApplicationClockChange() {
        var before=new HashSet<>(redis.keys("flashslot:rate:login:*"));
        var limiter=instance();
        String identity="expiry-"+UUID.randomUUID();
        for(int i=0;i<5;i++) assertThat(limiter.tryAcquireAccount(null,identity)).isZero();
        assertThat(limiter.tryAcquireAccount(null,identity)).isBetween(1L,60L);
        var created=new HashSet<>(redis.keys("flashslot:rate:login:*")); created.removeAll(before);
        assertThat(created).hasSize(1);
        String key=created.iterator().next();
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isBetween(1L,60000L);
        redis.expire(key,Duration.ofMillis(300));
        assertThat(limiter.tryAcquireAccount(null,identity)).isEqualTo(1);
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isBetween(0L,300L);
        await().atMost(Duration.ofSeconds(2)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
        assertThat(limiter.tryAcquireAccount(null,identity)).isZero();
    }
}
