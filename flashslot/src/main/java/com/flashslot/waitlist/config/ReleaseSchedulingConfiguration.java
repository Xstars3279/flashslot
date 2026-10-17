package com.flashslot.waitlist.config;

import com.flashslot.waitlist.service.ReleaseDispatchService;
import com.flashslot.waitlist.task.ReleaseJobScheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
        prefix = "app.release",
        name = "scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ReleaseSchedulingConfiguration {
    @Bean
    public ReleaseJobScheduler releaseJobScheduler(ReleaseDispatchService service) {
        return new ReleaseJobScheduler(service);
    }

    /** 独立调度线程，避免与 HTTP 请求线程共用；关闭应用时最多等待 5 秒。 */
    @Bean
    public ThreadPoolTaskScheduler releaseTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("release-scan-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
