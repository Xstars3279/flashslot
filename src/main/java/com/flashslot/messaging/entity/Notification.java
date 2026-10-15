package com.flashslot.messaging.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.flashslot.common.persistence.UtcInstantTypeHandler;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** 数据库表 notification 的持久化实体。 */
@Getter
@Setter
@NoArgsConstructor
@TableName(value = "notification", autoResultMap = true)
public class Notification {
    /** 内部自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属用户主键 */
    @TableField(value = "user_id")
    private Long userId;

    /** 稳定事件 UUID，重试不变 */
    @TableField(value = "event_id")
    private String eventId;

    /** 通知类型 */
    @TableField(value = "type")
    private String type;

    /** 带 schemaVersion 的 JSON 数据 */
    @TableField(value = "payload", typeHandler = JacksonTypeHandler.class)
    private JsonNode payload;

    /** 已读时间，UTC */
    @TableField(value = "read_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant readAt;

    /** 创建时间，UTC */
    @TableField(value = "created_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant createdAt;
}
