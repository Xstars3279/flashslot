package com.flashslot.auth.vo;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Token 仅返回客户端，不写数据库/幂等表，不提供包含 Token 的 toString。 */
@Getter
@AllArgsConstructor
public class LoginView {
    private String accessToken;
    private String tokenType;
    private Instant expiresAt;
    private LoginUserView user;
}
