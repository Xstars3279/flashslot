package com.flashslot.common.config;
import com.flashslot.common.service.HistoryCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration @EnableScheduling
@ConditionalOnProperty(prefix="app.history-cleanup",name="scheduling-enabled",havingValue="true",matchIfMissing=true)
public class HistoryCleanupConfiguration {
    @Bean public CleanupTask historyCleanupTask(HistoryCleanupService service) { return new CleanupTask(service); }
    @Bean public ThreadPoolTaskScheduler historyCleanupTaskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("history-cleanup-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true); scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
    @Slf4j @RequiredArgsConstructor
    public static class CleanupTask {
        private final HistoryCleanupService service;
        /** 每小时扫描，每次最多 20 个短事务；失败留待下轮。 */
        @Scheduled(fixedDelayString="${app.history-cleanup.scan-delay-ms:3600000}",initialDelayString="${app.history-cleanup.scan-delay-ms:3600000}",scheduler="historyCleanupTaskScheduler")
        public void scan() {
            try { for(int i=0;i<20;i++) if(service.cleanupBatch()==0) break; }
            catch(RuntimeException ex) { log.error("历史清理失败，异常类型={}，下轮重试",ex.getClass().getSimpleName()); }
        }
    }
}
