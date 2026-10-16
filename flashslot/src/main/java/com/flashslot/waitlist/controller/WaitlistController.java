package com.flashslot.waitlist.controller;

import com.flashslot.common.web.Result;
import com.flashslot.waitlist.dto.CreateWaitlistRequest;
import com.flashslot.waitlist.service.WaitlistService;
import com.flashslot.waitlist.vo.WaitlistView;

import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "候补")
@RestController
@RequestMapping("/api/v1/waitlists")
@RequiredArgsConstructor
public class WaitlistController {
    private final WaitlistService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<WaitlistView> create(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}") String key,
            @RequestBody @Valid CreateWaitlistRequest request) {
        return Result.success(service.create(key, request));
    }
}
