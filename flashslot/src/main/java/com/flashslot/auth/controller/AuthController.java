package com.flashslot.auth.controller;

import com.flashslot.auth.dto.LoginRequest;
import com.flashslot.auth.service.*;
import com.flashslot.auth.vo.LoginView;
import com.flashslot.common.web.*;

import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@Tag(name = "登录")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final LoginService service;
    private final LoginRateLimiter limiter;
    private final ClientIpResolver clientIp;
    private final TokenStateService tokens;

    @PostMapping("/logout")
    public Result<Void> logout(@org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt token) {
        tokens.revoke(token);
        return Result.success();
    }

    /** JSON 账号密码登录；禁止缓存响应，先限制 IP，再查询账号并执行账号限流及密码校验。 */
    @PostMapping("/login")
    public Result<LoginView> login(
            @RequestBody @Valid LoginRequest request,
            HttpServletRequest http,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        long retryAfter = limiter.tryAcquireIp(clientIp.resolve(http));
        if (retryAfter > 0) throw new LoginRateLimitException(retryAfter);
        return Result.success(service.login(request));
    }
}
