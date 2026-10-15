package com.flashslot.operations.controller;

import com.flashslot.common.web.Result;
import com.flashslot.operations.dto.*;
import com.flashslot.operations.service.*;
import com.flashslot.operations.vo.*;

import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "管理员恢复操作")
public class AdminRecoveryController {
    private final AccountRecoveryService accounts;
    private final MessageRecoveryService messages;
    private final ReleaseRecoveryService releases;

    @GetMapping("/release-jobs")
    public Result<RecoveryPage<ReleaseRecoveryView>> releases(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return Result.success(releases.list(cursor, limit));
    }

    @PostMapping("/release-jobs/{number}/retry")
    public Result<RecoveryActionView> retryRelease(
            @PathVariable String number,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody @Valid RecoveryRequest request) {
        return Result.success(releases.retry(number, key, request));
    }

    @PostMapping("/release-jobs/{number}/release")
    public Result<RecoveryActionView> release(
            @PathVariable String number,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody @Valid RecoveryRequest request) {
        return Result.success(releases.release(number, key, request));
    }

    @GetMapping("/messages")
    public Result<RecoveryPage<FailedMessageView>> messages(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return Result.success(messages.list(cursor, limit));
    }

    @PostMapping("/messages/{eventId}/retry")
    public Result<RecoveryActionView> retryMessage(
            @PathVariable String eventId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody @Valid RecoveryRequest request) {
        return Result.success(messages.retry(eventId, key, request));
    }

    @PostMapping("/users/{userId}/credit-adjustments")
    public Result<AccountRecoveryView> adjustCredit(
            @PathVariable long userId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody @Valid CreditAdjustmentRequest request) {
        return Result.success(accounts.adjustCredit(userId, key, request));
    }

    @PostMapping("/users/{userId}/restriction-clear")
    public Result<AccountRecoveryView> clearRestriction(
            @PathVariable long userId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody @Valid RecoveryRequest request) {
        return Result.success(accounts.clearRestriction(userId, key, request));
    }
}
