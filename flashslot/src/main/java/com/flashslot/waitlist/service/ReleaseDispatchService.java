package com.flashslot.waitlist.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.messaging.entity.OutboxEvent;
import com.flashslot.messaging.mapper.OutboxEventMapper;
import com.flashslot.messaging.model.PublishedEvent;
import com.flashslot.waitlist.mapper.ReleaseWorkMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 只可靠派发，不占用调度线程执行撮合。任务创建时立即派发，补偿扫描只补漏。 */
@Service @RequiredArgsConstructor
public class ReleaseDispatchService {
    /** 已发布但任务仍到期未推进时，等待该时长再补派，覆盖消息丢失、预算耗尽等情况。 */
    public static final Duration REDISPATCH_AFTER = Duration.ofMinutes(2);

    private final ReleaseWorkMapper work;
    private final OutboxEventMapper events;
    private final ObjectMapper json;
    private final Clock clock;

    /** 补偿：到期 PENDING 且没有在途或近期派发事件的任务才补派，broker 故障期间不会堆积重复指令。 */
    public int scan() {
        Instant now=clock.instant();
        int created=0;
        for(long id:work.findDispatchable(now,now.minus(REDISPATCH_AFTER))) created+=dispatch(id,now);
        return created;
    }

    /**
     * 与创建或重置任务处于同一事务时，事件随任务一起提交，由发布器秒级投递。
     * 同一任务同一分钟使用相同事件 ID，多实例或重复调用只生成一条。
     */
    public int dispatch(long jobId,Instant now) {
        var event=new OutboxEvent();
        Instant minute=now.truncatedTo(ChronoUnit.MINUTES);
        event.setEventId(UUID.nameUUIDFromBytes(("release-v1:"+jobId+":"+minute).getBytes(StandardCharsets.UTF_8)).toString());
        event.setEventType(PublishedEvent.RELEASE);
        event.setAggregateId(Long.toString(jobId));
        event.setPayload(json.createObjectNode().put("schemaVersion",1).put("jobId",jobId));
        event.setCreatedAt(now);
        event.setNextRunAt(now);
        return events.insertReleaseDispatch(event);
    }
}
