package com.flashslot.auth.service;

import com.flashslot.common.web.BusinessException;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 让 IP 与账号两个限流阶段返回一致状态和 Retry-After。 */
@Getter
public class LoginRateLimitException extends BusinessException {
    private final long retryAfter;
    public LoginRateLimitException(long retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS,"LOGIN_RATE_LIMITED","登录尝试过于频繁，请稍后重试");
        this.retryAfter=retryAfter;
    }
}
