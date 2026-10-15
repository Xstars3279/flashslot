package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flashslot.waitlist.config.ReleaseSchedulingConfiguration;
import com.flashslot.waitlist.service.ReleaseJobService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.support.CronExpression;

import java.time.*;

/** 验证真实注册的定时任务触发规则，不等待半小时，也不连接数据库。 */
class ReleaseSchedulingTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(ReleaseSchedulingConfiguration.class)
                    .withBean(ReleaseJobService.class, () -> mock(ReleaseJobService.class));

    @Test
    void registeredTaskRunsAtEveryHalfHour() {
        runner.run(
                context -> {
                    var tasks =
                            context.getBean(ScheduledAnnotationBeanPostProcessor.class)
                                    .getScheduledTasks();
                    assertThat(tasks).hasSize(1);
                    var task = tasks.iterator().next().getTask();
                    assertThat(task).isInstanceOf(CronTask.class);
                    var cron = CronExpression.parse(((CronTask) task).getExpression());
                    var first =
                            cron.next(
                                    ZonedDateTime.of(
                                            2026, 10, 15, 10, 1, 0, 0, ZoneId.of("Asia/Shanghai")));
                    assertThat(first)
                            .isEqualTo(
                                    ZonedDateTime.of(
                                            2026,
                                            10,
                                            15,
                                            10,
                                            30,
                                            0,
                                            0,
                                            ZoneId.of("Asia/Shanghai")));
                    assertThat(cron.next(first))
                            .isEqualTo(
                                    ZonedDateTime.of(
                                            2026, 10, 15, 11, 0, 0, 0, ZoneId.of("Asia/Shanghai")));
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
