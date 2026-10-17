package com.flashslot.resource.controller;

import com.flashslot.common.web.Result;
import com.flashslot.resource.dto.*;
import com.flashslot.resource.service.ResourceService;
import com.flashslot.resource.vo.ResourceView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name="设备管理")
@RestController
@RequestMapping("/api/v1/admin/resources")
@RequiredArgsConstructor
public class AdminResourceController {
    private final ResourceService service;

    @GetMapping
    public Result<com.flashslot.resource.vo.ResourcePage> list(@RequestParam(defaultValue="0") long cursor,@RequestParam(defaultValue="100") int limit) {
        return Result.success(service.listManaged(cursor,limit));
    }

    @PatchMapping("/{id}/status")
    public Result<ResourceView> status(@PathVariable @Positive long id,@RequestBody @Valid ResourceStatusRequest request) {
        return Result.success(service.changeStatus(id,request));
    }

    /** 接收设备 DTO，新建成功直接返回 Result，HTTP 状态为 201。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<ResourceView> create(@RequestBody @Valid CreateResourceRequest request) {
        return Result.success(service.create(request));
    }

    /** 局部修改设备配置，预期版本来自请求体中的 version 字段。 */
    @PatchMapping("/{id}")
    public Result<ResourceView> update(@PathVariable @Positive long id,
        @RequestBody @Valid UpdateResourceRequest request) {
        return Result.success(service.update(id, request));
    }
}
