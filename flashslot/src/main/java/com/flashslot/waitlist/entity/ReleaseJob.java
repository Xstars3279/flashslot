package com.flashslot.waitlist.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.flashslot.common.persistence.UtcInstantTypeHandler;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 数据库表 release_job 的持久化实体。 */
@Getter
@Setter
@NoArgsConstructor
@TableName(value = "release_job", autoResultMap = true)
public class ReleaseJob {
    /** 内部自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 对外随机交接任务号 */
    @TableField(value = "job_no")
    private String jobNo;

    /** 触发本次释放的旧订单主键 */
    @TableField(value = "source_booking_id")
    private Long sourceBookingId;

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

    /** 仍可交接区间的起始格，只能向后移动 */
    @TableField(value = "effective_start_slot")
    private Integer effectiveStartSlot;

    /** 业务状态，合法值由本表 CHECK 约束限定 */
    @TableField(value = "status")
    private String status;

    /** 连续失败或投递尝试计数 */
    @TableField(value = "attempts")
    private Integer attempts;

    /** 下一次处理时间，UTC */
    @TableField(value = "next_run_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant nextRunAt;

    /** 最近一次失败摘要，不写密钥 */
    @TableField(value = "last_error")
    private String lastError;

    /** 创建时间，UTC */
    @TableField(value = "created_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant createdAt;

    /** 更新时间，UTC */
    @TableField(value = "updated_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant updatedAt;
}
