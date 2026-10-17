package com.flashslot.operations.controller;
import com.flashslot.common.web.Result;
import com.flashslot.operations.dto.RecoveryRequest;
import com.flashslot.operations.service.DeadLetterRecoveryService;
import com.flashslot.operations.service.DeadLetterRecoveryService.DeadLetterView;
import com.flashslot.operations.vo.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/admin/dead-letters")
public class DeadLetterRecoveryController {
    private final DeadLetterRecoveryService service;
    @GetMapping
    public Result<RecoveryPage<DeadLetterView>> list(@RequestParam(defaultValue="0") long cursor,@RequestParam(defaultValue="20") int limit) { return Result.success(service.list(cursor,limit)); }
    @PostMapping("/{id}/replay")
    public Result<RecoveryActionView> replay(@PathVariable @Positive long id,@RequestHeader("Idempotency-Key") String key,@RequestBody @Valid RecoveryRequest request) { return Result.success(service.replay(id,key,request)); }
}
