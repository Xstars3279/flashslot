package com.flashslot.messaging.task;

import com.flashslot.messaging.service.OutboxPublisherService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@RequiredArgsConstructor
public class OutboxPublisherTask {
    private final OutboxPublisherService service;

    @Scheduled(
            fixedDelayString = "${app.messaging.scan-delay-ms:5000}",
            initialDelayString = "${app.messaging.scan-delay-ms:5000}",
            scheduler = "outboxTaskScheduler")
    public void scan() {
        try {
            service.scanDueEvents();
        } catch (RuntimeException ex) {
            log.warn("通知事件扫描失败，异常类型={}", ex.getClass().getSimpleName());
        }
    }
}
