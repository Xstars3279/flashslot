package com.flashslot.operations.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.flashslot.common.persistence.UtcInstantTypeHandler;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** 数据库表 admin_audit 的持久化实体。 */
@Getter
@Setter
@NoArgsConstructor
@TableName(value = "admin_audit", autoResultMap = true)
public class AdminAudit {
    /** 内部自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 管理员账号主键 */
    @TableField(value = "actor_id")
    private Long actorId;

    /** 固定操作类型，目标业务编号也纳入请求哈希 */
    @TableField(value = "operation")
    private String operation;

    /** 被操作的业务身份 */
    @TableField(value = "target_id")
    private String targetId;

    /** 拒绝或操作原因 */
    @TableField(value = "reason")
    private String reason;

    /** 链路追踪编号 */
    @TableField(value = "trace_id")
    private String traceId;

    /** 创建时间，UTC */
    @TableField(value = "created_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant createdAt;
}
