package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.flashslot.auth.service.LoginRateLimiter;
import java.time.*;
import static org.awaitility.Awaitility.await;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class LoginRateLimiterFallbackTest {
    @Test void outageRetainsRecentLocalAttemptsAndWindowExpires() {
        var redis=mock(StringRedisTemplate.class);
        var clock=mock(Clock.class);
        var now=Instant.parse("2026-10-17T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class)))
            .thenReturn(0L).thenThrow(new DataAccessResourceFailureException("offline"));
        var limiter=new LoginRateLimiter(redis,clock,30,5,60);
        for(int i=0;i<5;i++) assertThat(limiter.tryAcquireAccount(42L,"name")).isZero();
        assertThat(limiter.tryAcquireAccount(42L,"NAME")).isEqualTo(60);
        assertThat(limiter.tryAcquireAccount(43L,"other")).isZero();
        when(clock.instant()).thenReturn(now.plusSeconds(61));
        assertThat(limiter.tryAcquireAccount(42L,"name")).isZero();
    }

    @Test void fallbackCannotGrowPastItsCapacity() {
        var redis=mock(StringRedisTemplate.class);
        var limiter=new LoginRateLimiter(redis,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),30,5,60);
        for(int i=0;i<10000;i++) assertThat(limiter.tryAcquireAccount((long)i,"name")).isZero();
        assertThat(limiter.tryAcquireAccount(10000L,"name")).isEqualTo(60);
    }

    @Test void recoveredRedisReplacesLocalFallbackDecisionAfterBackoff() {
        var redis=mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class)))
            .thenThrow(new DataAccessResourceFailureException("offline")).thenReturn(17L);
        var limiter=new LoginRateLimiter(redis,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),30,5,60);
        assertThat(limiter.tryAcquireAccount(42L,"name")).isZero();
        // Redis reports a shared window with 17 seconds remaining; local fallback would report 0 or 60.
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() ->
            assertThat(limiter.tryAcquireAccount(42L,"name")).isEqualTo(17));
    }
}
