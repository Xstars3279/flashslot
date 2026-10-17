package com.flashslot.auth.service.impl;

import com.flashslot.auth.config.*;
import com.flashslot.auth.dto.LoginRequest;
import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.auth.model.LoginUserDetails;
import com.flashslot.auth.service.LoginRateLimitException;
import com.flashslot.auth.service.LoginRateLimiter;
import com.flashslot.auth.service.LoginService;
import com.flashslot.auth.vo.*;
import com.flashslot.common.web.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LoginServiceImpl implements LoginService {
    private final AuthenticationManager authenticationManager;
    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;
    private final AccountPermissionMapper accounts;
    private final LoginRateLimiter limiter;

    /** 密码认证成功后签发无状态 Token；不建立 Session，不记录明文密码或 Token。 */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public LoginView login(LoginRequest request) {
        Authentication authenticated;
        try {
            // IP 门禁在 Controller 执行；按数据库账号 ID 限流后，才进入昂贵的 BCrypt 校验。
            var account = accounts.findCurrentAccount(request.getUsername());
            long retryAfter =
                    limiter.tryAcquireAccount(
                            account == null ? null : account.getId(), request.getUsername());
            if (retryAfter > 0) throw new LoginRateLimitException(retryAfter);
            authenticated =
                    authenticationManager.authenticate(
                            UsernamePasswordAuthenticationToken.unauthenticated(
                                    request.getUsername(), request.getPassword()));
        } catch (AuthenticationServiceException | DataAccessException ex) {
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHENTICATION_UNAVAILABLE",
                    "登录服务暂时不可用，请稍后重试");
        } catch (AuthenticationException ex) {
            // 未知用户、密码错误、停用及非正式账号统一响应，不暴露账号状态。
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
        String role =
                authenticated
                        .getAuthorities()
                        .iterator()
                        .next()
                        .getAuthority()
                        .substring("ROLE_".length());
        LoginUserDetails user = (LoginUserDetails) authenticated.getPrincipal();
        // 与改密/停用串行化，防止认证期间凭据发生变化后仍签出可用 Token。
        var current = accounts.lockCredentials(user.getUserId());
        if(current==null || !Boolean.TRUE.equals(current.getEnabled())
                || current.getCredentialVersion()!=user.getCredentialVersion()
                || !role.equals(current.getRole()))
            throw new BusinessException(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","用户名或密码错误");
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plusSeconds(properties.getTtlSeconds());
        var claims =
                JwtClaimsSet.builder()
                        .id(java.util.UUID.randomUUID().toString())
                        .issuer(JwtConfiguration.ISSUER)
                        .audience(List.of(JwtConfiguration.AUDIENCE))
                        .subject(authenticated.getName())
                        .issuedAt(issuedAt)
                        .expiresAt(expiresAt)
                        .claim("userId", user.getUserId())
                        .claim("credentialVersion", user.getCredentialVersion())
                        .claim("roles", List.of(role))
                        .build();
        String token =
                encoder.encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                        .getTokenValue();
        return new LoginView(
                token, "Bearer", expiresAt, new LoginUserView(authenticated.getName(), role));
    }
}
