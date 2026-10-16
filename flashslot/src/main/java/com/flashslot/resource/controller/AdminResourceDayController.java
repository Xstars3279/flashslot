package com.flashslot.resource.controller;

import com.flashslot.common.web.Result;
import com.flashslot.resource.dto.UpdateResourceDayRequest;
import com.flashslot.resource.service.ResourceScheduleService;
import com.flashslot.resource.vo.ResourceDayView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@Tag(name="设备日历管理")
@RestController
@RequestMapping("/api/v1/admin/resources")
@RequiredArgsConstructor
public class AdminResourceDayController {
    private final ResourceScheduleService service;

    /** 修改一个已生成日历；date 使用 yyyy-MM-dd，预期 revision 与开放配置都在 DTO 中。 */
    @PutMapping("/{id}/days/{date}")
    public Result<ResourceDayView> updateDay(@PathVariable @Positive long id,
        @PathVariable @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestBody @Valid UpdateResourceDayRequest request) {
        return Result.success(service.updateDay(id,date,request));
    }
}
