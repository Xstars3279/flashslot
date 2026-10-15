package com.flashslot.messaging.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.flashslot.common.persistence.UtcInstantTypeHandler;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** 联合主键：consumer_name, event_id；使用显式 SQL，不使用单主键 CRUD。 */
@Getter
@Setter
@NoArgsConstructor
@TableName(value = "consumer_inbox", autoResultMap = true)
public class ConsumerInbox {
    /** 消费者身份 */
    @TableField(value = "consumer_name")
    private String consumerName;

    /** 稳定事件 UUID，重试不变 */
    @TableField(value = "event_id")
    private String eventId;

    /** 事务提交的消费完成时间，UTC */
    @TableField(value = "processed_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant processedAt;
}
