package com.flashslot.auth.config;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 密钥由 JWT_SECRET 提供；禁止自动生成固定开发密钥或打印配置对象。 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix="app.jwt")
public class JwtProperties {
    private String secret;
    @Min(60) @Max(86400)
    private long ttlSeconds=7200;
}
