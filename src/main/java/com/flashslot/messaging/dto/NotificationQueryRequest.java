package com.flashslot.messaging.dto;

import jakarta.validation.constraints.*;

import lombok.Data;

@Data
public class NotificationQueryRequest {
    @Positive private Long cursor;

    @Min(1)
    @Max(100)
    private int limit = 20;

    private boolean unreadOnly;
}
