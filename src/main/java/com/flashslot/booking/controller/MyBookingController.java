package com.flashslot.booking.controller;

import com.flashslot.booking.dto.BookingQueryRequest;
import com.flashslot.booking.service.BookingQueryService;
import com.flashslot.booking.vo.BookingPage;
import com.flashslot.common.web.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name="我的订单")
@RestController
@RequestMapping("/api/v1/me/bookings")
@RequiredArgsConstructor
public class MyBookingController {
    private final BookingQueryService service;

    /** 绑定分页参数，用户身份只能由服务端认证上下文取得。 */
    @GetMapping
    public Result<BookingPage> list(@ModelAttribute @Valid BookingQueryRequest request) {
        return Result.success(service.list(request.getCursor(),request.getLimit()));
    }
}
