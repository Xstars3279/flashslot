package com.flashslot.auth.controller;

import com.flashslot.auth.dto.RegistrationRequests;
import com.flashslot.auth.service.RegistrationService;
import com.flashslot.auth.vo.LoginView;
import com.flashslot.common.web.Result;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class RegistrationController {
    private final RegistrationService service;
    private final com.flashslot.auth.service.ClientIpResolver clientIp;
    public record CodeView(String developmentCode,int expiresIn,int retryAfter,String mode) {}
    @PostMapping("/registration/code")
    public Result<CodeView> code(@RequestBody @Valid RegistrationRequests.Phone body,HttpServletRequest request,HttpServletResponse response) {
        noCache(response);
        return Result.success(new CodeView(service.sendCode(body.getPhone(),clientIp.resolve(request)),300,60,"DEVELOPMENT"));
    }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public Result<LoginView> register(@RequestBody @Valid RegistrationRequests.Register body,HttpServletResponse response) {
        noCache(response); return Result.success(service.register(body));
    }
    private void noCache(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); response.setHeader("Pragma","no-cache"); }
}
