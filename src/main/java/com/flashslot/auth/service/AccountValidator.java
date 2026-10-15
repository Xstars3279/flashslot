package com.flashslot.auth.service;

import com.flashslot.auth.entity.AppUser;
import com.flashslot.common.web.BusinessException;

import org.springframework.http.HttpStatus;

/** 只检查已加载的账号，不查库、不取锁，也不改变当前请求身份。 */
public final class AccountValidator {
    private AccountValidator() {}

    public static AppUser requireEnabled(AppUser account) {
        return requireEnabled(account, "账号不存在");
    }

    public static AppUser requireEnabled(AppUser account, String missingMessage) {
        if (account == null)
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", missingMessage);
        if (!isEnabled(account))
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号已停用");
        return account;
    }

    public static AppUser requireFormal(AppUser account) {
        return requireFormal(account, "账号不可用");
    }

    public static AppUser requireFormal(AppUser account, String unavailableMessage) {
        if (!isFormal(account))
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", unavailableMessage);
        return requireEnabled(account);
    }

    public static boolean isEnabled(AppUser account) {
        return account != null && Boolean.TRUE.equals(account.getEnabled());
    }

    public static boolean isFormal(AppUser account) {
        return account != null
                && ("USER".equals(account.getRole()) || "ADMIN".equals(account.getRole()));
    }
}
