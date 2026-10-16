package com.flashslot.resource.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.flashslot.common.persistence.UtcInstantTypeHandler;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 联合主键：resource_id, book_date；使用显式 SQL，不使用单主键 CRUD。 */
@Getter
@Setter
@NoArgsConstructor
@TableName(value = "resource_day", autoResultMap = true)
public class ResourceDay {
    /** 所属设备主键 */
    @TableField(value = "resource_id")
    private Long resourceId;

    /** 业务日期，Asia/Shanghai */
    @TableField(value = "book_date")
    private LocalDate bookDate;

    /** 当天开放起始格，包含；默认 08:00 */
    @TableField(value = "open_start_slot")
    private Integer openStartSlot;

    /** 当天开放结束格，不包含；默认 22:00 */
    @TableField(value = "open_end_slot")
    private Integer openEndSlot;

    /** 当天是否开放：1 是，0 否 */
    @TableField(value = "enabled")
    private Boolean enabled;

    /** 当天排期版本，相关业务变更时由应用加 1 */
    @TableField(value = "revision")
    private Long revision;

    /** 创建时间，UTC */
    @TableField(value = "created_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant createdAt;

    /** 更新时间，UTC */
    @TableField(value = "updated_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant updatedAt;
}
