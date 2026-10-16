package com.flashslot.messaging.config;

import com.flashslot.messaging.service.OutboxPublisherService;
import com.flashslot.messaging.task.OutboxPublisherTask;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
        prefix = "app.messaging",
        name = {"enabled", "scheduling-enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class OutboxSchedulingConfiguration {
    @Bean
    public OutboxPublisherTask outboxPublisherTask(OutboxPublisherService service) {
        return new OutboxPublisherTask(service);
    }

    @Bean
    public ThreadPoolTaskScheduler outboxTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("outbox-publish-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
