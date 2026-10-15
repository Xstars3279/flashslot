package com.flashslot.booking.config;

import com.flashslot.booking.service.BookingExpiryService;
import com.flashslot.booking.task.BookingExpiryScheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
        prefix = "app.booking-expiry",
        name = "scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BookingExpirySchedulingConfiguration {
    @Bean
    public BookingExpiryScheduler bookingExpiryScheduler(BookingExpiryService service) {
        return new BookingExpiryScheduler(service);
    }

    /** 独立线程，半小时候补撮合耗时不会阻塞爽约与完成扫描。 */
    @Bean
    public ThreadPoolTaskScheduler bookingExpiryTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("booking-expiry-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
