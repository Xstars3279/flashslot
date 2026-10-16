package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flashslot.messaging.config.OutboxSchedulingConfiguration;
import com.flashslot.messaging.service.OutboxPublisherService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;

import java.time.Duration;

class OutboxSchedulingTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(OutboxSchedulingConfiguration.class)
                    .withBean(
                            OutboxPublisherService.class, () -> mock(OutboxPublisherService.class));

    @Test
    void publisherRegistersFiveSecondScan() {
        runner.run(
                context -> {
                    var tasks =
                            context.getBean(ScheduledAnnotationBeanPostProcessor.class)
                                    .getScheduledTasks();
                    assertThat(tasks).hasSize(1);
                    var task = (FixedDelayTask) tasks.iterator().next().getTask();
                    assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofSeconds(5));
                });
    }

    @Test
    void disabledMessagingDoesNotCreatePublisherTask() {
        runner.withPropertyValues("app.messaging.enabled=false")
                .run(
                        context ->
                                assertThat(
                                                context.getBeansOfType(
                                                        ScheduledAnnotationBeanPostProcessor.class))
                                        .isEmpty());
    }
}
