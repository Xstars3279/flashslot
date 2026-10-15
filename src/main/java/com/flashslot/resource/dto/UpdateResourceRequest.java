package com.flashslot.resource.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/** PATCH 请求：version 必填，其余配置字段的 null 表示不修改。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateResourceRequest {
    /** 查询设备详情时取得的配置版本，用于乐观锁校验。 */
    @NotNull @PositiveOrZero
    private Long version;

    @Size(min=1, max=128)
    private String name;

    @Size(min=1, max=128)
    private String location;

    @Min(0) @Max(47)
    private Integer defaultOpenStartSlot;

    @Min(1) @Max(48)
    private Integer defaultOpenEndSlot;

    @NotBlank @Size(max=256)
    private String reason;
}
