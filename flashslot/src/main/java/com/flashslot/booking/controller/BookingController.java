package com.flashslot.booking.controller;

import com.flashslot.booking.dto.CancelBookingRequest;
import com.flashslot.booking.dto.CreateBookingRequest;
import com.flashslot.booking.service.BookingCancellationService;
import com.flashslot.booking.service.BookingQueryService;
import com.flashslot.booking.service.BookingService;
import com.flashslot.booking.vo.BookingDetailView;
import com.flashslot.booking.vo.BookingView;
import com.flashslot.common.web.Result;

import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "预约")
@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {
    private final BookingService service;
    private final BookingQueryService queries;
    private final BookingCancellationService cancellations;

    /** 取消本人订单或有审计地取消他人订单，返回当前状态和释放进度。 */
    @PostMapping("/{orderNo}/cancel")
    public Result<BookingDetailView> cancel(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}") String orderNo,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}") String key,
            @RequestBody @Valid CancelBookingRequest request) {
        return Result.success(cancellations.cancel(orderNo, key, request));
    }

    /** 返回本人或有效管理员可见的订单当前状态，无法查看的订单统一返回 404。 */
    @GetMapping("/{orderNo}")
    public Result<BookingDetailView> get(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}") String orderNo) {
        return Result.success(queries.get(orderNo));
    }

    /** 创建或回放首次结果；用户 ID 不允许由请求体指定，保留 CSRF 保护。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<BookingView> create(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}") String key,
            @RequestBody @Valid CreateBookingRequest request) {
        return Result.success(service.create(key, request));
    }
}
