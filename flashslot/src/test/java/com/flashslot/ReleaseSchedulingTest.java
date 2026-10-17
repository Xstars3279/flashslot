package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flashslot.waitlist.config.ReleaseSchedulingConfiguration;
import com.flashslot.waitlist.service.ReleaseDispatchService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;

import java.time.Duration;

/** 验证真实注册的补偿任务，不连接数据库。撮合本身在任务创建时立即派发。 */
class ReleaseSchedulingTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(ReleaseSchedulingConfiguration.class)
                    .withBean(ReleaseDispatchService.class, () -> mock(ReleaseDispatchService.class));

    @Test
    void onlyCompensationRunsEveryMinute() {
        runner.run(
                context -> {
                    var tasks =
                            context.getBean(ScheduledAnnotationBeanPostProcessor.class)
                                    .getScheduledTasks();
                    assertThat(tasks).hasSize(1);
                    var task = tasks.iterator().next().getTask();
                    assertThat(task).isInstanceOf(FixedDelayTask.class);
                    assertThat(((FixedDelayTask) task).getIntervalDuration())
                            .isEqualTo(Duration.ofMinutes(1));
                });
    }

    @Test
    void disabledSchedulingDoesNotRegisterBackgroundTasks() {
        runner.withPropertyValues("app.release.scheduling-enabled=false")
                .run(
                        context ->
                                assertThat(
                                                context.getBeansOfType(
                                                        ScheduledAnnotationBeanPostProcessor.class))
                                        .isEmpty());
    }
}
