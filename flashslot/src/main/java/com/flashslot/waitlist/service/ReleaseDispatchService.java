package com.flashslot.waitlist.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.messaging.entity.OutboxEvent;
import com.flashslot.messaging.mapper.OutboxEventMapper;
import com.flashslot.messaging.model.PublishedEvent;
import com.flashslot.waitlist.mapper.ReleaseWorkMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 只可靠派发，不占用调度线程执行撮合。每个任务每半小时至多生成一个事件。 */
@Service @RequiredArgsConstructor
public class ReleaseDispatchService {
    private final ReleaseWorkMapper work;
    private final OutboxEventMapper events;
    private final ObjectMapper json;
    private final Clock clock;

    public int scan() {
        Instant boundary=Instant.ofEpochSecond(Math.floorDiv(clock.instant().getEpochSecond(),1800)*1800);
        int created=0;
        for(long id:work.findDispatchable(boundary)) {
            var event=new OutboxEvent();
            event.setEventId(UUID.nameUUIDFromBytes(("release-v1:"+id+":"+boundary).getBytes(StandardCharsets.UTF_8)).toString());
            event.setEventType(PublishedEvent.RELEASE);
            event.setAggregateId(Long.toString(id));
            event.setPayload(json.createObjectNode().put("schemaVersion",1).put("jobId",id));
            event.setCreatedAt(boundary);
            event.setNextRunAt(clock.instant());
            created+=events.insertReleaseDispatch(event);
        }
        return created;
    }
}
