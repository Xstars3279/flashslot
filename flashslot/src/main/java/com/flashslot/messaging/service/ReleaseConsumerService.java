package com.flashslot.messaging.service;

import com.flashslot.messaging.mapper.ConsumerInboxMapper;
import com.flashslot.messaging.metrics.MessageProcessingMetrics;
import com.flashslot.messaging.model.*;
import com.flashslot.waitlist.service.ReleaseJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 不用长事务包裹多候选撮合。短事务已提交的状态负责崩溃恢复，Inbox 过滤已完成的重复消息。 */
@Service @RequiredArgsConstructor
public class ReleaseConsumerService {
    private final ReleaseJobService jobs;
    private final ConsumerInboxMapper inbox;
    private final MessageProcessingMetrics metrics;
    public void consume(NotificationEvent event) {
        metrics.consume("release", () -> consumeAttempt(event));
    }
    private void consumeAttempt(NotificationEvent event) {
        PublishedEvent.validate(event);
        if(!PublishedEvent.RELEASE.equals(event.eventType())) throw new IllegalArgumentException("不是撮合指令");
        if(inbox.releaseCompleted(event.eventId())>0) return;
        jobs.processMessage(event.payload().path("jobId").asLong());
        // 崩溃发生在这里之前时，重投会检查任务终态/候补状态，已创建的订单不会再创建。
        inbox.completeRelease(event.eventId());
    }
}
