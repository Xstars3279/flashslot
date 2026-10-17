package com.flashslot.waitlist.task;

import com.flashslot.waitlist.service.ReleaseDispatchService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;

/** 上海时间每个整点、半点扫描，不在 HTTP 取消事务里同步撮合。 */
@RequiredArgsConstructor
@Slf4j
public class ReleaseJobScheduler {
    private final ReleaseDispatchService service;

    @Scheduled(
            cron = "${app.release.scan-cron:0 */30 * * * *}",
            zone = "Asia/Shanghai",
            scheduler = "releaseTaskScheduler")
    public void scan() {
        try {
            service.scan();
        } catch (RuntimeException ex) {
            log.warn("释放任务扫描失败，exception={}", ex.getClass().getSimpleName());
        }
    }

    /** 重启、漏触发和超过一批的积压由补偿扫描补派，实际到期条件仍取最近半小时。 */
    @Scheduled(fixedDelayString="${app.release.compensation-delay-ms:60000}", scheduler="releaseTaskScheduler")
    public void compensate() { scan(); }
}
