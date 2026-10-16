package com.flashslot.resource.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/** 新增设备请求，Spring 通过无参构造和 Setter 绑定 JSON。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateResourceRequest {
    @NotBlank @Size(max=128)
    private String name;

    @NotBlank @Size(max=128)
    private String location;

    @NotNull @Min(0) @Max(47)
    private Integer defaultOpenStartSlot;

    @NotNull @Min(1) @Max(48)
    private Integer defaultOpenEndSlot;

    @NotBlank @Size(max=256)
    private String reason;
}
