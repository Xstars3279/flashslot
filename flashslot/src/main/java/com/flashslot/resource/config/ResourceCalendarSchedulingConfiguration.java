package com.flashslot.resource.config;

import com.flashslot.resource.mapper.ResourceMapper;
import com.flashslot.resource.service.ResourceCalendarService;
import com.flashslot.resource.task.ResourceCalendarScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.resource-calendar", name = "scheduling-enabled",
    havingValue = "true", matchIfMissing = true)
public class ResourceCalendarSchedulingConfiguration {
    @Bean
    public ResourceCalendarScheduler resourceCalendarScheduler(ResourceMapper resources, ResourceCalendarService service) {
        return new ResourceCalendarScheduler(resources, service);
    }

    @Bean
    public ThreadPoolTaskScheduler resourceCalendarTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("resource-calendar-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
