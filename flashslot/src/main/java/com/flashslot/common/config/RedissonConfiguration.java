package com.flashslot.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.*;

@Configuration
public class RedissonConfiguration {
    @Bean(destroyMethod="shutdown")
    public RedissonClient cacheRedisson(RedisProperties redis,@Value("${app.cache-lock.watchdog-timeout-ms:30000}") long watchdog) {
        var config=new Config();
        // 延迟连接，Redis故障时应用仍可启动并通过数据库维持业务正确性。
        config.setLazyInitialization(true).setThreads(2).setNettyThreads(2).setLockWatchdogTimeout(watchdog);
        var server=config.useSingleServer().setAddress((redis.getSsl().isEnabled()?"rediss://":"redis://")+redis.getHost()+":"+redis.getPort())
            .setDatabase(redis.getDatabase()).setConnectTimeout(1500).setTimeout(1500).setRetryAttempts(0)
            .setConnectionMinimumIdleSize(1).setConnectionPoolSize(8).setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        if(redis.getPassword()!=null && !redis.getPassword().isBlank()) server.setPassword(redis.getPassword());
        if(redis.getUsername()!=null && !redis.getUsername().isBlank()) server.setUsername(redis.getUsername());
        return Redisson.create(config);
    }
}
