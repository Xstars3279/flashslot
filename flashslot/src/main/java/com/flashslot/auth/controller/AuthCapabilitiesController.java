package com.flashslot.auth.controller;

import com.flashslot.auth.service.RegistrationCodeService;
import com.flashslot.common.web.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class AuthCapabilitiesController {
    private final RegistrationCodeService registration;
    public record Capabilities(boolean registrationEnabled,String registrationMode) {}
    @GetMapping("/api/v1/auth/capabilities")
    public Result<Capabilities> capabilities(HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        boolean enabled=registration.isEnabled();
        return Result.success(new Capabilities(enabled,enabled?"DEVELOPMENT":"DISABLED"));
    }
}
