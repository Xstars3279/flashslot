package com.flashslot.auth.service;

import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis 共享窗口；本机影子计数在 Redis 故障时提供有界降级保护。 */
@Component
@Slf4j
public class LoginRateLimiter {
    private static final int MAX_BUCKETS=10000;
    private static final long RETRY_NANOS=Duration.ofSeconds(2).toNanos();
    private static final DefaultRedisScript<Long> ACQUIRE=new DefaultRedisScript<>("""
        local count=tonumber(redis.call('GET',KEYS[1]) or '0')
        local ttl=redis.call('PTTL',KEYS[1])
        if count>=tonumber(ARGV[1]) then
          if ttl<0 then redis.call('PEXPIRE',KEYS[1],ARGV[2]); ttl=tonumber(ARGV[2]) end
          return math.max(1,math.ceil(ttl/1000))
        end
        redis.call('INCR',KEYS[1])
        if ttl<0 then redis.call('PEXPIRE',KEYS[1],ARGV[2]) end
        return 0
        """,Long.class);
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final int ipLimit,accountLimit,windowSeconds;
    private final AtomicLong retryAfter=new AtomicLong();
    private final Map<String,Window> windows=new HashMap<>();

    public LoginRateLimiter(StringRedisTemplate redis,Clock clock,
            @Value("${app.login-rate-limit.ip-limit:30}") int ipLimit,
            @Value("${app.login-rate-limit.account-limit:5}") int accountLimit,
            @Value("${app.login-rate-limit.window-seconds:60}") int windowSeconds) {
        if(ipLimit<1 || accountLimit<1 || windowSeconds<1)
            throw new IllegalArgumentException("登录限流额度和窗口必须为正数");
        this.redis=redis; this.clock=clock; this.ipLimit=ipLimit;
        this.accountLimit=accountLimit; this.windowSeconds=windowSeconds;
    }

    /** 在数据库查询前限制已由可信代理规则解析的来源 IP。 */
    public long tryAcquireIp(String remoteAddress) {
        return acquire("ip:"+remoteAddress,ipLimit);
    }

    /** 数据库匹配到的账号以 ID 计数，确保大小写、重音及兼容字符等别名共用窗口。 */
    public long tryAcquireAccount(Long accountId,String suppliedUsername) {
        String identity=accountId==null ? "unknown:"+suppliedUsername.strip().toLowerCase(Locale.ROOT) : "id:"+accountId;
        return acquire("account:"+identity,accountLimit);
    }

    /** 返回 0 表示放行，其余为 Retry-After 秒数；网络访问不持有本机计数锁。 */
    private long acquire(String key,int limit) {
        long localDelay=acquireLocal(key,limit);
        long deadline=retryAfter.get();
        if(deadline!=0 && System.nanoTime()-deadline<0) return localDelay;
        try {
            Long result=redis.execute(ACQUIRE,List.of("flashslot:rate:login:"+hash(key)),
                Integer.toString(limit),Long.toString(windowSeconds*1000L));
            if(result!=null && result>=0) return result;
        } catch(DataAccessException ex) {
            // 不记录账号、IP 或 Redis 连接凭据。
        }
        long now=System.nanoTime();
        deadline=retryAfter.get();
        if((deadline==0 || now-deadline>=0) && retryAfter.compareAndSet(deadline,now+RETRY_NANOS))
            log.warn("登录限流 Redis 不可用，暂用本机限流；2秒后尝试恢复共享计数");
        return localDelay;
    }

    private synchronized long acquireLocal(String key,int limit) {
        Instant now=clock.instant();
        windows.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt));
        Window window=windows.get(key);
        long delay=retryAfter(window,limit,now);
        if (delay>0) return delay;
        if (window==null && windows.size()>=MAX_BUCKETS) return windowSeconds;
        windows.computeIfAbsent(key,ignored -> new Window(now.plusSeconds(windowSeconds))).attempts++;
        return 0;
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private long retryAfter(Window window,int limit,Instant now) {
        return window!=null && window.attempts>=limit ? Math.max(1,(Duration.between(now,window.expiresAt).toMillis()+999)/1000) : 0;
    }
    private static class Window {
        private final Instant expiresAt;
        private int attempts;
        private Window(Instant expiresAt) { this.expiresAt=expiresAt; }
    }
}
