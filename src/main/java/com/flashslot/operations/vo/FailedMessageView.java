package com.flashslot.operations.vo;

import java.time.Instant;

public record FailedMessageView(
        String eventId,
        String eventType,
        String aggregateId,
        int attempts,
        String lastError,
        Instant createdAt) {}
