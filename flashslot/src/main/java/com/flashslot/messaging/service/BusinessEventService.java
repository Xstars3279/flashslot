package com.flashslot.messaging.service;

import com.fasterxml.jackson.databind.*;
import com.flashslot.messaging.entity.OutboxEvent;
import com.flashslot.messaging.mapper.OutboxEventMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.UUID;

/** 业务事件只持久化，投递器由后续消息模块实现。 */
@Service
@RequiredArgsConstructor
public class BusinessEventService {
    private final OutboxEventMapper mapper;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String type, String aggregateId, long userId, Object data, Instant now) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(type);
        event.setStatus(com.flashslot.messaging.model.PublishedEvent.initialStatus(type));
        event.setAggregateId(aggregateId);
        event.setPayload(
                json.createObjectNode()
                        .put("schemaVersion", 1)
                        .put("userId", userId)
                        .set("data", json.valueToTree(data)));
        event.setNextRunAt(now);
        if (mapper.insert(event) != 1) throw new IllegalStateException("事件保存失败");
    }
}
