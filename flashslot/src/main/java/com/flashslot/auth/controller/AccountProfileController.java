package com.flashslot.auth.controller;

import com.flashslot.auth.service.AccountProfileService;
import com.flashslot.auth.vo.AccountProfileView;
import com.flashslot.common.web.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/profile")
@RequiredArgsConstructor
public class AccountProfileController {
    private final AccountProfileService service;

    @GetMapping
    public Result<AccountProfileView> get(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Result.success(service.getCurrent());
    }
}
