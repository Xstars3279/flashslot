package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flashslot.booking.config.BookingExpirySchedulingConfiguration;
import com.flashslot.booking.service.BookingExpiryService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;

class BookingExpirySchedulingTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(BookingExpirySchedulingConfiguration.class)
                    .withBean(BookingExpiryService.class, () -> mock(BookingExpiryService.class));

    @Test
    void registeredTaskUsesFiveSecondDelay() {
        runner.run(
                context -> {
                    var tasks =
                            context.getBean(ScheduledAnnotationBeanPostProcessor.class)
                                    .getScheduledTasks();
                    assertThat(tasks).hasSize(1);
                    var task = tasks.iterator().next().getTask();
                    assertThat(task).isInstanceOf(FixedDelayTask.class);
                    assertThat(((FixedDelayTask) task).getIntervalDuration())
                            .isEqualTo(java.time.Duration.ofSeconds(5));
                    assertThat(((FixedDelayTask) task).getInitialDelayDuration())
                            .isEqualTo(java.time.Duration.ofSeconds(5));
                });
    }

    @Test
    void disabledSchedulingCreatesNoBackgroundTask() {
        runner.withPropertyValues("app.booking-expiry.scheduling-enabled=false")
                .run(
                        context ->
                                assertThat(
                                                context.getBeansOfType(
                                                        ScheduledAnnotationBeanPostProcessor.class))
                                        .isEmpty());
    }
}
