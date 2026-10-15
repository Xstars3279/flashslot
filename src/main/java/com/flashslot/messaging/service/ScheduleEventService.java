package com.flashslot.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.messaging.entity.OutboxEvent;
import com.flashslot.messaging.mapper.OutboxEventMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ScheduleEventService {
    private final OutboxEventMapper mapper;
    private final ObjectMapper json;

    /** 将排期变更持久化到当前事务；此处不调用 MQ，事件投递器以后实现。 revision 是本次变更后的排期版本，eventId 在将来的重试投递中保持不变。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long resourceId, LocalDate date, long revision, Instant now) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("SCHEDULE_CHANGED");
        event.setAggregateId(resourceId + ":" + date);
        event.setPayload(
                json.createObjectNode()
                        .put("schemaVersion", 1)
                        .put("resourceId", resourceId)
                        .put("bookDate", date.toString())
                        .put("revision", revision));
        event.setNextRunAt(now);
        mapper.insert(event);
    }
}
