package com.flashslot.resource.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.resource.model.ScheduleOccupation;
import com.flashslot.resource.service.ScheduleCacheService;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 只缓存公开占用，不保存身份、订单号或计算后的可预约标记。日历版本变化立即换键。 */
@Service
@Slf4j
public class ScheduleCacheServiceImpl implements ScheduleCacheService {
    @Autowired private StringRedisTemplate redis;
    @Autowired private ObjectMapper json;

    @Value("${app.schedule-cache.enabled:true}")
    private boolean enabled;

    private final AtomicLong retryAfter = new AtomicLong();

    @Override
    public List<ScheduleOccupation> get(long id, LocalDate date, long revision) {
        if (!available()) return null;
        try {
            String value = redis.opsForValue().get(key(id, date, revision));
            if (value == null) return null;
            return json.readValue(value, new TypeReference<List<ScheduleOccupation>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // 损坏缓存按未命中处理，本次查询随后覆盖，不能让排期接口失败。
            return null;
        } catch (RuntimeException ex) {
            unavailable(ex);
            return null;
        }
    }

    @Override
    public void put(long id, LocalDate date, long revision, List<ScheduleOccupation> occupations) {
        if (!available()) return;
        // 防止未来修改调用方时意外把个人订单信息写入共享缓存。
        if (occupations.stream().anyMatch(o -> o.getOwnOrderNo() != null))
            throw new IllegalArgumentException("共享排期缓存不能包含订单号");
        try {
            redis.opsForValue()
                    .set(
                            key(id, date, revision),
                            json.writeValueAsString(occupations),
                            Duration.ofSeconds(30));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            log.warn("排期缓存序列化失败");
        } catch (RuntimeException ex) {
            unavailable(ex);
        }
    }

    private String key(long id, LocalDate date, long revision) {
        return "flashslot:schedule:" + id + ":" + date + ":" + revision;
    }

    private boolean available() {
        return enabled && System.nanoTime() >= retryAfter.get();
    }

    private void unavailable(RuntimeException ex) {
        retryAfter.set(System.nanoTime() + Duration.ofSeconds(15).toNanos());
        log.warn("Redis排期缓存暂不可用，15秒内查询回源数据库，exception={}", ex.getClass().getSimpleName());
    }
}
