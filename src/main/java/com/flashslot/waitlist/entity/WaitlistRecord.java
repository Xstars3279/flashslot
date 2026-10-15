package com.flashslot.waitlist.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.flashslot.common.persistence.UtcInstantTypeHandler;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 数据库表 waitlist_record 的持久化实体。 */
@Getter
@Setter
@NoArgsConstructor
@TableName(value = "waitlist_record", autoResultMap = true)
public class WaitlistRecord {
    /** 内部自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 对外随机候补号 */
    @TableField(value = "waitlist_no")
    private String waitlistNo;

    /** 所属用户主键 */
    @TableField(value = "user_id")
    private Long userId;

    /** 所属设备主键 */
    @TableField(value = "resource_id")
    private Long resourceId;

    /** 业务日期，Asia/Shanghai */
    @TableField(value = "book_date")
    private LocalDate bookDate;

    /** 起始格，包含 */
    @TableField(value = "start_slot")
    private Integer startSlot;

    /** 结束格，不包含 */
    @TableField(value = "end_slot")
    private Integer endSlot;

    /** 用途枚举 */
    @TableField(value = "purpose")
    private String purpose;

    /** 业务状态，合法值由本表 CHECK 约束限定 */
    @TableField(value = "status")
    private String status;

    /** 拒绝或操作原因 */
    @TableField(value = "reason")
    private String reason;

    /** 候补转正截止时间，UTC */
    @TableField(value = "expire_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant expireAt;

    /** 已转正订单主键 */
    @TableField(value = "matched_booking_id")
    private Long matchedBookingId;

    /** WAITING 时生成 1，终态为 NULL，用于只限制有效候补重复 */
    @TableField(value = "active_marker", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Integer activeMarker;

    /** 创建时间，UTC */
    @TableField(value = "created_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant createdAt;

    /** 更新时间，UTC */
    @TableField(value = "updated_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant updatedAt;
}
