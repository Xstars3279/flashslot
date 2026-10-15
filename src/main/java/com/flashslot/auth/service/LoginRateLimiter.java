package com.flashslot.auth.service;

import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 单实例登录保护：每 IP 每分钟 30 次、每账号每分钟 5 次，成功和失败都计数。 */
@Component
@RequiredArgsConstructor
public class LoginRateLimiter {
    private static final int MAX_BUCKETS=10000;
    private final Clock clock;
    private final Map<String,Window> windows=new HashMap<>();

    /** 在数据库查询前限制来源 IP，不信任客户端转发头。 */
    public synchronized long tryAcquireIp(String remoteAddress) {
        return acquire("ip:"+remoteAddress,30);
    }

    /** 数据库匹配到的账号以 ID 计数，确保大小写、重音及兼容字符等别名共用窗口。 */
    public synchronized long tryAcquireAccount(Long accountId,String suppliedUsername) {
        String identity=accountId==null ? "unknown:"+suppliedUsername.strip().toLowerCase(Locale.ROOT) : "id:"+accountId;
        return acquire("account:"+identity,5);
    }

    /** 两个入口共用有界窗口；返回 0 表示放行，其余为 Retry-After 秒数。 */
    private long acquire(String key,int limit) {
        Instant now=clock.instant();
        windows.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt));
        Window window=windows.get(key);
        long delay=retryAfter(window,limit,now);
        if (delay>0) return delay;
        if (window==null && windows.size()>=MAX_BUCKETS) return 60;
        windows.computeIfAbsent(key,ignored -> new Window(now.plusSeconds(60))).attempts++;
        return 0;
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
