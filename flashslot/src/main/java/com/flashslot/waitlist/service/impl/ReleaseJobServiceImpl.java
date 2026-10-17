package com.flashslot.waitlist.service.impl;

import com.flashslot.waitlist.mapper.ReleaseWorkMapper;
import com.flashslot.waitlist.service.ReleaseJobService;
import com.flashslot.waitlist.service.WaitlistMatchingService;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.*;

/** 每个候选独立事务；扫描预算不意味着可以跳过未处理队列直接释放库存。 */
@Service
@Slf4j
public class ReleaseJobServiceImpl implements ReleaseJobService {
    @Autowired private ReleaseWorkMapper workMapper;
    @Autowired private Clock clock;
    @Autowired private WaitlistMatchingService matchingService;

    @Override
    public int scanDueJobs() {
        var jobs = workMapper.findDue(clock.instant());
        for (long id : jobs) process(id);
        return jobs.size();
    }

    @Override
    public void process(long jobId) {
        try {
            processMessage(jobId);
        } catch (RuntimeException ex) {
            log.warn("释放任务处理失败，jobId={}，exception={}", jobId, ex.getClass().getSimpleName());
            try { matchingService.recordFailure(jobId,ex); }
            catch(RuntimeException recordingFailure) {
                log.warn("释放任务失败记录未保存，jobId={}，exception={}",jobId,recordingFailure.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void processMessage(long jobId) {
        for(int checked=0;checked<20;checked++) {
            if(matchingService.matchNext(jobId)) return;
        }
        // 预算耗尽保留 PENDING，由补偿扫描在两分钟后补派继续，不提前释放。
        // MQ 重试耗尽后由 recoverer 记业务退避，不能提前改 nextRunAt 导致下次消费被误判完成。
    }
}
