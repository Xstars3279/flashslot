package com.flashslot.booking.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.flashslot.common.persistence.UtcInstantTypeHandler;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 数据库表 booking_order 的持久化实体。 */
@Getter
@Setter
@NoArgsConstructor
@TableName(value = "booking_order", autoResultMap = true)
public class BookingOrder {
    /** 内部自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 对外随机业务订单号 */
    @TableField(value = "order_no")
    private String orderNo;

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

    /** 当前计入每日使用量的格数，支持将来汇总配额 */
    @TableField(value = "charged_slots")
    private Integer chargedSlots;

    /** 业务状态，合法值由本表 CHECK 约束限定 */
    @TableField(value = "status")
    private String status;

    /** DIRECT 直接预约，WAITLIST 候补转正 */
    @TableField(value = "source")
    private String source;

    /** 用途枚举 */
    @TableField(value = "purpose")
    private String purpose;

    /** 转正来源候补，仅候补订单有值 */
    @TableField(value = "waitlist_id")
    private Long waitlistId;

    /** 交接任务主键 */
    @TableField(value = "release_job_id")
    private Long releaseJobId;

    /** 预约开始时间，UTC */
    @TableField(value = "start_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant startAt;

    /** 签到窗口开始，UTC */
    @TableField(value = "check_in_open_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant checkInOpenAt;

    /** 签到截止，不包含该时刻，UTC */
    @TableField(value = "check_in_deadline", typeHandler = UtcInstantTypeHandler.class)
    private Instant checkInDeadline;

    /** 预约结束时间，UTC */
    @TableField(value = "end_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant endAt;

    /** 实际签到时间，UTC */
    @TableField(value = "checked_in_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant checkedInAt;

    /** 取消或爽约判定时间，UTC */
    @TableField(value = "terminated_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant terminatedAt;

    /** USER_CANCEL、EARLY_FINISH、ADMIN_CANCEL 或 NO_SHOW */
    @TableField(value = "termination_reason")
    private String terminationReason;

    /** 管理取消等操作说明 */
    @TableField(value = "reason_text")
    private String reasonText;

    /** 业务记录版本，由应用维护 */
    @TableField(value = "version")
    private Long version;

    /** 创建时间，UTC */
    @TableField(value = "created_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant createdAt;

    /** 更新时间，UTC */
    @TableField(value = "updated_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant updatedAt;
}
