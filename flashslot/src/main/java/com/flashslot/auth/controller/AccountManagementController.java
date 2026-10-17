package com.flashslot.auth.controller;

import com.flashslot.auth.dto.AccountRequests.*;
import com.flashslot.auth.service.AccountManagementService;
import com.flashslot.auth.vo.ManagedAccountView;
import com.flashslot.common.web.Result;
import com.flashslot.operations.vo.RecoveryPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AccountManagementController {
    private final AccountManagementService service;

    @GetMapping("/admin/users")
    public Result<RecoveryPage<ManagedAccountView>> list(@RequestParam(defaultValue="0") long cursor,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String username) {
        return Result.success(service.list(cursor,limit,username));
    }
    @PostMapping("/admin/users") @ResponseStatus(HttpStatus.CREATED)
    public Result<ManagedAccountView> create(@RequestBody @Valid CreateAccount request) { return Result.success(service.create(request)); }
    @PostMapping("/me/password")
    public Result<Void> password(@RequestBody @Valid ChangePassword request) { service.changePassword(request); return Result.success(); }
    @PostMapping("/admin/users/{id}/password-reset")
    public Result<Void> reset(@PathVariable @Positive long id,@RequestBody @Valid ResetPassword request) { service.reset(id,request); return Result.success(); }
    @PatchMapping("/admin/users/{id}/status")
    public Result<ManagedAccountView> status(@PathVariable @Positive long id,@RequestBody @Valid AccountStatus request) { return Result.success(service.status(id,request)); }
}
