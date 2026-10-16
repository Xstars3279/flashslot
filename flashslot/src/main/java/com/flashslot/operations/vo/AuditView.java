package com.flashslot.operations.vo;

import lombok.Data;

import java.time.Instant;

@Data
public class AuditView {
    private Long id;
    private Long actorId;
    private String actorUsername;
    private String operation;
    private String targetId;
    private String reason;
    private String traceId;
    private Instant createdAt;
}
