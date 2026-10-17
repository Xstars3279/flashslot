package com.flashslot.messaging.entity;
import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @TableName("dead_letter")
public class DeadLetter {
    @TableId(type=IdType.AUTO) private Long id;
    private String messageHash;
    private byte[] body;
    private String eventId;
    private String eventType;
    private String errorCode;
    private String status;
    private Integer deliveries;
    private Instant receivedAt;
    private Instant recoveredAt;
    private String sourceQueue;
    private String originalExchange;
    private String routingKey;
    private String failureCategory;
    private String failureReason;
    private Long brokerDeathCount;
    private Instant lastReceivedAt;
}
