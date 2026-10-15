package com.flashslot.operations.dto;

import jakarta.validation.constraints.*;

import lombok.Data;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;

@Data
public class AuditQueryRequest {
    @Positive private Long cursor;

    @Min(1)
    @Max(100)
    private int limit = 20;

    @Positive private Long actorId;

    @Pattern(regexp = "[A-Z_]{1,64}")
    private String operation;

    @Size(max = 64)
    private String targetId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant to;
}
