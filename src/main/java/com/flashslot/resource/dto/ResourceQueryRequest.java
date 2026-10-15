package com.flashslot.resource.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/** GET 查询参数通过 @ModelAttribute 绑定，默认每页 20 条。 */
@Data
@NoArgsConstructor
public class ResourceQueryRequest {
    @PositiveOrZero
    private long cursor = 0;

    @Min(1) @Max(100)
    private int limit = 20;
}
