package com.flashslot.messaging.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.flashslot.common.persistence.UtcInstantTypeHandler;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** 数据库表 outbox_event 的持久化实体。 */
@Getter
@Setter
@NoArgsConstructor
@TableName(value = "outbox_event", autoResultMap = true)
public class OutboxEvent {
    /** 稳定事件 UUID，重试不变 */
    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;

    /** 事件类型 */
    @TableField(value = "event_type")
    private String eventType;

    /** 关联业务编号 */
    @TableField(value = "aggregate_id")
    private String aggregateId;

    /** 带 schemaVersion 的 JSON 数据 */
    @TableField(value = "payload", typeHandler = JacksonTypeHandler.class)
    private JsonNode payload;

    /** 业务状态，合法值由本表 CHECK 约束限定 */
    @TableField(value = "status")
    private String status;

    /** 连续失败或投递尝试计数 */
    @TableField(value = "attempts")
    private Integer attempts;

    /** 下一次处理时间，UTC */
    @TableField(value = "next_run_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant nextRunAt;

    /** 本次领取的随机租约令牌 */
    @TableField(value = "lease_token")
    private String leaseToken;

    /** 租约到期时间，UTC */
    @TableField(value = "lease_until", typeHandler = UtcInstantTypeHandler.class)
    private Instant leaseUntil;

    /** 最近一次失败摘要，不写密钥 */
    @TableField(value = "last_error")
    private String lastError;

    /** 创建时间，UTC */
    @TableField(value = "created_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant createdAt;

    /** broker 确认成功时间，非消费完成时间 */
    @TableField(value = "published_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant publishedAt;
}
