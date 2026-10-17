package com.flashslot.waitlist.service;

/** 释放任务扫描与撮合业务，后台执行不依赖登录请求上下文。 */
public interface ReleaseJobService {
    /** 扫描一批已到处理时间的任务，返回本次发现的任务数。 */
    int scanDueJobs();

    /** 处理一个持久化任务；终态任务重复执行无副作用。 */
    void process(long jobId);

    /** MQ 路径必须抛出异常，由监听器重试或死信；不能把未完成处理误 ACK。 */
    void processMessage(long jobId);
}
