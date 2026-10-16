package com.flashslot.waitlist.task;

import com.flashslot.waitlist.service.ReleaseJobService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;

/** 上海时间每个整点、半点扫描，不在 HTTP 取消事务里同步撮合。 */
@RequiredArgsConstructor
@Slf4j
public class ReleaseJobScheduler {
    private final ReleaseJobService service;

    @Scheduled(
            cron = "${app.release.scan-cron:0 */30 * * * *}",
            zone = "Asia/Shanghai",
            scheduler = "releaseTaskScheduler")
    public void scan() {
        try {
            service.scanDueJobs();
        } catch (RuntimeException ex) {
            log.warn("释放任务扫描失败，exception={}", ex.getClass().getSimpleName());
        }
    }
}
