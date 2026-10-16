package com.flashslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.flashslot.resource.config.ResourceCalendarSchedulingConfiguration;
import com.flashslot.resource.entity.Resource;
import com.flashslot.resource.mapper.ResourceMapper;
import com.flashslot.resource.service.ResourceCalendarService;
import com.flashslot.resource.task.ResourceCalendarScheduler;
import java.time.Duration;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;

class ResourceCalendarSchedulingTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ResourceCalendarSchedulingConfiguration.class)
        .withBean(ResourceMapper.class, () -> mock(ResourceMapper.class))
        .withBean(ResourceCalendarService.class, () -> mock(ResourceCalendarService.class));

    @Test
    void registersMinuteTaskAndCanDisableAllBackgroundWork() {
        runner.run(context -> {
            var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
            assertThat(tasks).hasSize(1);
            var task = (FixedDelayTask) tasks.iterator().next().getTask();
            assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(1));
            assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofMinutes(1));
        });
        runner.withPropertyValues("app.resource-calendar.scheduling-enabled=false").run(context ->
            assertThat(context.getBeansOfType(ResourceCalendarScheduler.class)).isEmpty());
    }

    @Test
    void startupRecoversEveryPageAndOneResourceFailureDoesNotBlockOthers() {
        var mapper = mock(ResourceMapper.class);
        var service = mock(ResourceCalendarService.class);
        List<Resource> page = LongStream.rangeClosed(1, 100).mapToObj(id -> {
            var resource = new Resource(); resource.setId(id); return resource;
        }).toList();
        var last = new Resource(); last.setId(101L);
        when(mapper.selectList(any())).thenReturn(page, List.of(last));
        doThrow(new IllegalStateException("test failure")).when(service).ensureWindow(1L);
        new ResourceCalendarScheduler(mapper, service).onReady();
        verify(service).ensureWindow(2L);
        verify(service).ensureWindow(101L);
        verify(service, times(101)).ensureWindow(anyLong());
        verify(mapper, times(2)).selectList(any());
    }

    @Test
    void scanFailureCanRecoverOnNextInvocation() {
        var mapper = mock(ResourceMapper.class);
        var service = mock(ResourceCalendarService.class);
        var resource = new Resource(); resource.setId(1L);
        when(mapper.selectList(any())).thenThrow(new IllegalStateException("unavailable"))
            .thenReturn(List.of(resource));
        var scheduler = new ResourceCalendarScheduler(mapper, service);
        scheduler.scan();
        scheduler.scan();
        verify(service).ensureWindow(1L);
    }
}
