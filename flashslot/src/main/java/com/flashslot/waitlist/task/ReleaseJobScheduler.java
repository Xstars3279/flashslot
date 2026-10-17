package com.flashslot.waitlist.task;

import com.flashslot.waitlist.service.ReleaseDispatchService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;

/** 任务创建时已立即派发；这里只补派重启、消息丢失、失败退避到期和超过一批的积压。 */
@RequiredArgsConstructor
@Slf4j
public class ReleaseJobScheduler {
    private final ReleaseDispatchService service;

    @Scheduled(fixedDelayString="${app.release.compensation-delay-ms:60000}", scheduler="releaseTaskScheduler")
    public void compensate() {
        try {
            service.scan();
        } catch (RuntimeException ex) {
            log.warn("释放任务补偿扫描失败，exception={}", ex.getClass().getSimpleName());
        }
    }
}
