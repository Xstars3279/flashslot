package com.flashslot.waitlist.service;

/** 候补撮合业务：每个候选和失败记录分别提交，扫描器不持有批量事务。 */
public interface WaitlistMatchingService {
    boolean matchNext(long jobId);

    void recordFailure(long jobId, RuntimeException error);
}
