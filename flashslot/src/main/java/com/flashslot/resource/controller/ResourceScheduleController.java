package com.flashslot.resource.controller;

import com.flashslot.common.web.Result;
import com.flashslot.resource.dto.ScheduleQueryRequest;
import com.flashslot.resource.service.ResourceScheduleService;
import com.flashslot.resource.vo.ScheduleView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name="设备排期")
@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class ResourceScheduleController {
    private final ResourceScheduleService service;

    /** 查询上海业务日期的单日排期，结果放在统一响应的 data 中。 */
    @GetMapping("/{id}/schedule")
    public Result<ScheduleView> getSchedule(@PathVariable @Positive long id,
        @ModelAttribute @Valid ScheduleQueryRequest request) {
        return Result.success(service.getSchedule(id,request.getDate()));
    }
}
