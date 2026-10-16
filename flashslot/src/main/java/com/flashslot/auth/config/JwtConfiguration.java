package com.flashslot.auth.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.time.*;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** 固定 HS256、签发方和受众；缺少外部密钥时启动失败，不提供默认秘密。 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {
    public static final String ISSUER = "flashslot";
    public static final String AUDIENCE = "flashslot-api";

    /** JWT_SECRET 必须是至少 32 个随机字节的标准 Base64 编码，错误信息不包含密钥。 */
    @Bean
    public SecretKey jwtSigningKey(JwtProperties properties) {
        byte[] bytes;
        try {
            bytes =
                    Base64.getDecoder()
                            .decode(properties.getSecret() == null ? "" : properties.getSecret());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "JWT_SECRET must be Base64 encoded random key material");
        }
        if (bytes.length < 32)
            throw new IllegalStateException(
                    "JWT_SECRET must contain at least 32 random bytes after Base64 decoding");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    /** 使用可注入 Clock 验证时间；严格在 exp 到达时失效，且要求完整的本应用身份声明。 */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSigningKey, Clock clock) {
        var decoder =
                NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                        .macAlgorithm(MacAlgorithm.HS256)
                        .build();
        var timestamp = new JwtTimestampValidator(Duration.ZERO);
        timestamp.setClock(clock);
        OAuth2TokenValidator<Jwt> required =
                token -> {
                    Instant now = clock.instant();
                    Object userId = token.getClaim("userId");
                    boolean valid =
                            (userId instanceof Long || userId instanceof Integer)
                                    && ((Number) userId).longValue() > 0
                                    && token.getSubject() != null
                                    && !token.getSubject().isBlank()
                                    && token.getSubject().length() <= 64
                                    && token.getIssuedAt() != null
                                    && token.getExpiresAt() != null
                                    && !token.getIssuedAt().isAfter(now)
                                    && token.getExpiresAt().isAfter(token.getIssuedAt())
                                    && now.isBefore(token.getExpiresAt());
                    return valid
                            ? OAuth2TokenValidatorResult.success()
                            : OAuth2TokenValidatorResult.failure(
                                    new OAuth2Error(
                                            "invalid_token",
                                            "Invalid identity or time claims",
                                            null));
                };
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        timestamp,
                        new JwtIssuerValidator(ISSUER),
                        new JwtClaimValidator<java.util.List<String>>(
                                "aud", aud -> aud != null && aud.contains(AUDIENCE)),
                        required));
        return decoder;
    }
}
