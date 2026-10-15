package com.flashslot.waitlist.controller;

import com.flashslot.common.web.Result;
import com.flashslot.waitlist.dto.WaitlistQueryRequest;
import com.flashslot.waitlist.service.WaitlistService;
import com.flashslot.waitlist.vo.WaitlistPage;

import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@Tag(name = "我的候补")
@RestController
@RequestMapping("/api/v1/me/waitlists")
@RequiredArgsConstructor
public class MyWaitlistController {
    private final WaitlistService service;

    @PostMapping("/{number}/cancel")
    public Result<com.flashslot.waitlist.vo.WaitlistView> cancel(
            @PathVariable String number, @RequestHeader("Idempotency-Key") String key) {
        return Result.success(service.cancel(number, key));
    }

    @GetMapping
    public Result<WaitlistPage> list(@ModelAttribute @Valid WaitlistQueryRequest request) {
        return Result.success(service.list(request.getCursor(), request.getLimit()));
    }
}
