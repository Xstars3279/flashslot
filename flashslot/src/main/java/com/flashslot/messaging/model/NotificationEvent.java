package com.flashslot.messaging.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** 稳定事件编号用于消费防重；不携带登录凭据。 */
public record NotificationEvent(
        String eventId,
        String eventType,
        String aggregateId,
        Instant occurredAt,
        JsonNode payload) {
    public void validate() {
        if (eventId == null
                || !UUID.fromString(eventId).toString().equals(eventId)
                || !NotificationEventType.supported(eventType)
                || aggregateId == null
                || aggregateId.isBlank()
                || aggregateId.length() > 64
                || occurredAt == null
                || payload == null
                || !payload.isObject()
                || !payload.path("schemaVersion").isIntegralNumber()
                || !payload.path("schemaVersion").canConvertToInt()
                || payload.path("schemaVersion").asInt() != 1
                || !payload.path("userId").isIntegralNumber()
                || !payload.path("userId").canConvertToLong()
                || payload.path("userId").asLong() <= 0
                || !data().isObject()) throw new IllegalArgumentException("不支持的通知事件结构");
    }

    /** 兼容最早创建预约事件中的 booking 字段。 */
    public JsonNode data() {
        return payload.has("data") ? payload.path("data") : payload.path("booking");
    }
}
