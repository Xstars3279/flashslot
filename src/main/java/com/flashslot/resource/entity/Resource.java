package com.flashslot.resource.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.flashslot.common.persistence.UtcInstantTypeHandler;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 数据库表 resource 的持久化实体。 */
@Getter
@Setter
@NoArgsConstructor
@TableName(value = "resource", autoResultMap = true)
public class Resource {
    /** 设备主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 设备名称，例如 3D 打印机 01 */
    @TableField(value = "name")
    private String name;

    /** 设备位置，例如实验楼 A201 */
    @TableField(value = "location")
    private String location;

    /** ACTIVE 正常；PAUSED 暂停新申请；RETIRED 退役 */
    @TableField(value = "status")
    private String status;

    /** 默认开放起始格，包含；16 对应 08:00 */
    @TableField(value = "default_open_start_slot")
    private Integer defaultOpenStartSlot;

    /** 默认开放结束格，不包含；44 对应 22:00 */
    @TableField(value = "default_open_end_slot")
    private Integer defaultOpenEndSlot;

    /** 配置乐观锁版本，成功修改时由应用加 1 */
    @Version
    @TableField(value = "version")
    private Long version;

    /** 创建时间，UTC */
    @TableField(value = "created_at", typeHandler = UtcInstantTypeHandler.class,
        insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    /** 更新时间，UTC */
    @TableField(value = "updated_at", typeHandler = UtcInstantTypeHandler.class,
        insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
