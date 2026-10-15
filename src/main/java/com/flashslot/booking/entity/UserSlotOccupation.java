package com.flashslot.booking.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 联合主键：user_id, book_date, slot_index；使用显式 SQL，不使用单主键 CRUD。 */
@Getter
@Setter
@NoArgsConstructor
@TableName(value = "user_slot_occupation", autoResultMap = true)
public class UserSlotOccupation {
    /** 所属用户主键 */
    @TableField(value = "user_id")
    private Long userId;

    /** 业务日期，Asia/Shanghai */
    @TableField(value = "book_date")
    private LocalDate bookDate;

    /** 30 分钟时间格编号，范围 0 到 47 */
    @TableField(value = "slot_index")
    private Integer slotIndex;

    /** 精确归属订单主键 */
    @TableField(value = "booking_id")
    private Long bookingId;
}
