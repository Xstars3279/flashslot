package com.flashslot.auth.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Redis only accelerates rejection; an absent or unavailable entry always falls back to SQL. */
@Service
public class AccountBanCache {
    private static final Logger LOG=LoggerFactory.getLogger(AccountBanCache.class);
    private static final long RETRY_NANOS=java.time.Duration.ofSeconds(2).toNanos();
    private static final String PREFIX="flashslot:auth:account-ban:";
    // Retain enabled version tombstones so a delayed disable cannot overwrite a newer enable.
    // Compare decimal strings rather than Lua doubles, preserving the whole BIGINT range.
    private static final DefaultRedisScript<Long> UPDATE=new DefaultRedisScript<>("""
        local current=redis.call('GET',KEYS[1])
        if current then
          local version=string.match(current,'^(%d+):[01]$')
          if version and (#version>#ARGV[1] or (#version==#ARGV[1] and version>=ARGV[1])) then
            return 0
          end
        end
        redis.call('SET',KEYS[1],ARGV[1]..':'..ARGV[2])
        return 1
        """,Long.class);
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final AtomicLong retryAfter=new AtomicLong();

    public AccountBanCache(StringRedisTemplate redis,
            @Value("${app.account-ban-cache.enabled:true}") boolean enabled) {
        this.redis=redis;
        this.enabled=enabled;
    }

    public boolean isBlocked(long id,long credentialVersion) {
        if(!available()) return false;
        try {
            String value=redis.opsForValue().get(PREFIX+id);
            if(value==null || !value.endsWith(":1")) return false;
            long version=Long.parseLong(value.substring(0,value.length()-2));
            return version>=credentialVersion;
        } catch(RuntimeException ex) {
            unavailable();
            return false;
        }
    }

    public void synchronizeAfterCommit(long id,long credentialVersion,boolean accountEnabled) {
        if(!enabled) return;
        if(TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { synchronize(id,credentialVersion,accountEnabled); }
            });
        } else synchronize(id,credentialVersion,accountEnabled);
    }

    private void synchronize(long id,long credentialVersion,boolean accountEnabled) {
        if(!available()) return;
        try {
            redis.execute(UPDATE,List.of(PREFIX+id),Long.toString(credentialVersion),accountEnabled?"0":"1");
        } catch(RuntimeException ex) { unavailable(); }
    }

    private boolean available() {
        long deadline=retryAfter.get();
        return enabled && (deadline==0 || System.nanoTime()-deadline>=0);
    }

    private void unavailable() {
        long now=System.nanoTime();
        long deadline=retryAfter.get();
        if((deadline==0 || now-deadline>=0) && retryAfter.compareAndSet(deadline,now+RETRY_NANOS))
            LOG.warn("Account ban cache unavailable; using persistent account state");
    }
}
