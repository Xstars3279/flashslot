package com.flashslot.booking.controller;

import com.flashslot.booking.dto.CheckInRequest;
import com.flashslot.booking.service.CheckInService;
import com.flashslot.booking.vo.BookingDetailView;
import com.flashslot.booking.vo.CheckInCodeView;
import com.flashslot.common.web.Result;

import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@Tag(name = "预约签到")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CheckInController {
    private final CheckInService service;

    /** 管理员获取设备签到码供现场展示，禁止缓存。 */
    @GetMapping("/admin/resources/{id}/check-in-code")
    public Result<CheckInCodeView> code(
            @PathVariable @Positive long id, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Result.success(service.issueCode(id));
    }

    /** 本人签到；状态转换自身幂等，重复请求不更新首次签到时间。 */
    @PostMapping("/bookings/{orderNo}/check-in")
    public Result<BookingDetailView> checkIn(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}") String orderNo,
            @RequestBody @Valid CheckInRequest request) {
        return Result.success(service.checkIn(orderNo, request.getCheckInCode()));
    }
}
