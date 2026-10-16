package com.flashslot.resource.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flashslot.resource.entity.Resource;
import com.flashslot.resource.mapper.ResourceMapper;
import com.flashslot.resource.service.ResourceCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@RequiredArgsConstructor
public class ResourceCalendarScheduler {
    private final ResourceMapper resources;
    private final ResourceCalendarService service;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() { scan(); }

    /** 启动恢复及每分钟补齐；逐设备事务，单台失败留待下一轮重试。 */
    @Scheduled(fixedDelayString = "${app.resource-calendar.scan-delay-ms:60000}",
        initialDelayString = "${app.resource-calendar.scan-delay-ms:60000}",
        scheduler = "resourceCalendarTaskScheduler")
    public void scan() {
        try {
            long cursor = 0;
            while (true) {
                var page = resources.selectList(Wrappers.<Resource>lambdaQuery()
                    .ne(Resource::getStatus, "RETIRED").gt(Resource::getId, cursor)
                    .orderByAsc(Resource::getId).last("LIMIT 100"));
                for (var resource : page) {
                    try { service.ensureWindow(resource.getId()); }
                    catch (RuntimeException ex) {
                        log.error("设备日历补齐失败，resourceId={}，异常类型={}，下一轮重试",
                            resource.getId(), ex.getClass().getSimpleName());
                    }
                }
                if (page.size() < 100) return;
                cursor = page.getLast().getId();
            }
        } catch (RuntimeException ex) {
            log.error("设备日历扫描失败，异常类型={}，下一轮重试", ex.getClass().getSimpleName());
        }
    }
}
