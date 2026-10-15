package com.flashslot.resource.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 管理员完整替换某天的开放配置，revision 来自排期查询，不修改其他日期。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateResourceDayRequest {
    @NotNull @PositiveOrZero
    private Long revision;
    @NotNull
    private Boolean enabled;
    @NotNull @Min(0) @Max(47)
    private Integer openStartSlot;
    @NotNull @Min(1) @Max(48)
    private Integer openEndSlot;
    @NotBlank @Size(max=256)
    private String reason;
}
