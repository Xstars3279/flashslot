package com.flashslot.booking.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 联合主键：resource_id, book_date, slot_index；使用显式 SQL，不使用单主键 CRUD。 */
@Getter
@Setter
@NoArgsConstructor
@TableName(value = "slot_occupation", autoResultMap = true)
public class SlotOccupation {
    /** 所属设备主键 */
    @TableField(value = "resource_id")
    private Long resourceId;

    /** 业务日期，Asia/Shanghai */
    @TableField(value = "book_date")
    private LocalDate bookDate;

    /** 30 分钟时间格编号，范围 0 到 47 */
    @TableField(value = "slot_index")
    private Integer slotIndex;

    /** BOOKED 已分配，HANDOFF 候补交接中 */
    @TableField(value = "state")
    private String state;

    /** 精确归属订单主键 */
    @TableField(value = "booking_id")
    private Long bookingId;

    /** 交接任务主键 */
    @TableField(value = "release_job_id")
    private Long releaseJobId;
}
