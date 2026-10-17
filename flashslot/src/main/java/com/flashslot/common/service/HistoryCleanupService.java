package com.flashslot.common.service;
import com.flashslot.common.mapper.HistoryCleanupMapper;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class HistoryCleanupService {
    private final HistoryCleanupMapper mapper;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Value("${app.history-cleanup.event-history-retention-days:90}")
    private int historyDays;
    /** 每批独立提交；只清理过期的留痕事件，不清理发布中/已发布事件或 Inbox 防重记录。 */
    @Transactional
    public int cleanupBatch() {
        var now=clock.instant();
        if(historyDays<1) throw new IllegalStateException("事件历史保留天数必须为正数");
        return mapper.completedOperations(now)+mapper.revokedTokens(now)
            +mapper.archivedEvents(now.minus(java.time.Duration.ofDays(historyDays)));
    }
}
