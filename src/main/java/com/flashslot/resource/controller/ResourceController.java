package com.flashslot.resource.controller;

import com.flashslot.resource.service.ResourceService;
import com.flashslot.common.web.Result;
import com.flashslot.resource.dto.ResourceQueryRequest;
import jakarta.validation.Valid;
import com.flashslot.resource.vo.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name="设备查询")
@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class ResourceController {
    private final ResourceService service;

    /** 将 URL 查询参数绑定到 DTO，并返回统一包装的游标分页结果。 */
    @GetMapping
    public Result<ResourcePage> list(@ModelAttribute @Valid ResourceQueryRequest request) {
        return Result.success(service.list(request.getCursor(), request.getLimit()));
    }

    /** 查询设备详情，data.version 用于后续配置修改时的乐观锁校验。 */
    @GetMapping("/{id}")
    public Result<ResourceView> get(@PathVariable @Positive long id) {
        return Result.success(service.get(id));
    }
}
