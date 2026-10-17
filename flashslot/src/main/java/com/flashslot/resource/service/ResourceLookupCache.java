package com.flashslot.resource.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.common.web.BusinessException;
import com.flashslot.resource.entity.Resource;
import com.flashslot.resource.mapper.ResourceMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

/** 数据库version决定缓存键；版本和详情在同一只读快照中读取。 */
@Service @RequiredArgsConstructor @Slf4j
public class ResourceLookupCache {
    private final org.springframework.data.redis.core.StringRedisTemplate redis;
    private final RedissonClient locks;
    private final ObjectMapper json;
    private final ResourceMapper mapper;
    private final HotResourcePolicy policy;
    @Value("${app.schedule-cache.enabled:true}") private boolean enabled;
    private final AtomicLong retryAfter=new AtomicLong();
    private record Cached(boolean hit,Resource resource) {}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Resource find(long id) {
        if(!available()) return mapper.selectById(id);
        Long version=mapper.findVersion(id);
        String cacheKey=key(id,version);
        var cached=read(cacheKey,id,version);
        if(cached.hit()) return cached.resource();
        if(!available()) return mapper.selectById(id);
        RLock lock=locks.getLock("flashslot:lock:resource:"+id+":"+suffix(version));
        boolean acquired;
        try { acquired=lock.tryLock(1,TimeUnit.SECONDS); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw busy(); }
        catch(RuntimeException ex) { unavailable(ex); return mapper.selectById(id); }
        if(!acquired) { cached=read(cacheKey,id,version); if(cached.hit()) return cached.resource(); throw busy(); }
        try {
            cached=read(cacheKey,id,version); if(cached.hit()) return cached.resource();
            Resource found=mapper.selectById(id);
            if(found==null ? version!=null : !Objects.equals(version,found.getVersion()))
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"CACHE_SNAPSHOT_CHANGED","设备版本发生变化，请重试");
            try {
                int ttl=found==null?5+ThreadLocalRandom.current().nextInt(6):policy.isHot(found)?120+ThreadLocalRandom.current().nextInt(61):20+ThreadLocalRandom.current().nextInt(21);
                // 旧请求即使晚到，也只写自己的旧版本键。
                redis.opsForValue().set(cacheKey,json.writeValueAsString(found),Duration.ofSeconds(ttl));
            } catch(Exception ex) { unavailable(ex); }
            return found;
        } finally { release(lock); }
    }

    /** 提交后回收旧键；即使删除失败，新请求仍按数据库新版本访问新键。 */
    public void invalidateAfterCommit(long id,Long oldVersion) {
        if(!enabled) return;
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("设备缓存失效必须绑定事务");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                var keys=new ArrayList<String>();
                keys.add(key(id,null));
                if(oldVersion!=null) keys.add(key(id,oldVersion));
                keys.add("flashslot:resource:"+id);
                try { redis.delete(keys); } catch(RuntimeException ex) { unavailable(ex); }
            }
        });
    }
    private Cached read(String cacheKey,long id,Long version) {
        try {
            String value=redis.opsForValue().get(cacheKey);
            if(value==null) return new Cached(false,null);
            Resource resource=json.readValue(value,Resource.class);
            if(resource==null) return new Cached(version==null,null);
            return new Cached(resource.getId()!=null && resource.getId()==id && Objects.equals(version,resource.getVersion()),resource);
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) { return new Cached(false,null); }
        catch(RuntimeException ex) { unavailable(ex); return new Cached(false,null); }
    }
    private void release(RLock lock) {
        try { if(lock.isHeldByCurrentThread()) lock.unlock(); } catch(RuntimeException ex) { unavailable(ex); }
    }
    private boolean available() { long deadline=retryAfter.get(); return enabled && (deadline==0 || System.nanoTime()-deadline>=0); }
    private void unavailable(Exception ex) { retryAfter.set(System.nanoTime()+Duration.ofSeconds(15).toNanos()); log.warn("设备缓存暂不可用，查询回源数据库，exception={}",ex.getClass().getSimpleName()); }
    private String key(long id,Long version) { return "flashslot:resource:"+id+":"+suffix(version); }
    private String suffix(Long version) { return version==null?"missing":version.toString(); }
    private BusinessException busy() { return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"CACHE_BUSY","设备加载繁忙，请稍后重试"); }
}
