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
    /** 每批独立提交，已完成幂等记录保留至 expires_at；不删除业务与去重历史。 */
    @Transactional
    public int cleanupBatch() {
        var now=clock.instant();
        return mapper.completedOperations(now)+mapper.revokedTokens(now);
    }
}
