package com.flashslot.credit.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.flashslot.common.persistence.UtcInstantTypeHandler;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 数据库表 credit_ledger 的持久化实体。 */
@Getter
@Setter
@NoArgsConstructor
@TableName(value = "credit_ledger", autoResultMap = true)
public class CreditLedger {
    /** 内部自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属用户主键 */
    @TableField(value = "user_id")
    private Long userId;

    /** 事件类型 */
    @TableField(value = "event_type")
    private String eventType;

    /** 业务事件身份，防止重复信用变更 */
    @TableField(value = "business_id")
    private String businessId;

    /** 实际分值差，可为 0 */
    @TableField(value = "delta")
    private Integer delta;

    /** 变更前分值 */
    @TableField(value = "score_before")
    private Integer scoreBefore;

    /** 变更后分值 */
    @TableField(value = "score_after")
    private Integer scoreAfter;

    /** 拒绝或操作原因 */
    @TableField(value = "reason")
    private String reason;

    /** 创建时间，UTC */
    @TableField(value = "created_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant createdAt;
}
