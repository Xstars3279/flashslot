package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.resource.service.impl.ScheduleCacheServiceImpl;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

class ScheduleCacheFallbackTest {
    @Test
    void redisOutageIsTreatedAsMissAndTemporarilySkipsFurtherNetworkCalls() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("offline"));
        var cache = new ScheduleCacheServiceImpl();
        ReflectionTestUtils.setField(cache, "redis", redis);
        ReflectionTestUtils.setField(cache, "json", new ObjectMapper());
        ReflectionTestUtils.setField(cache, "enabled", true);
        var date = LocalDate.of(2026, 10, 16);
        assertThat(cache.get(1, date, 1)).isNull();
        assertThat(cache.get(1, date, 1)).isNull();
        cache.put(1, date, 1, List.of());
        verify(redis, times(1)).opsForValue();
    }
}
