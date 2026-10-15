package com.flashslot.common.context;

import com.flashslot.auth.model.LoginUserDetails;
import com.flashslot.common.web.BusinessException;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** 当前请求身份入口，复用 Spring Security 的 ThreadLocal 生命周期，不另存身份或查库。 */
public final class BaseContext {
    private BaseContext() {}

    /** 受保护业务获取当前用户 ID，身份缺失统一返回 401。 */
    public static Long getCurrentId() {
        Long userId = getCurrentIdOrNull();
        if (userId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
        }
        return userId;
    }

    /** 公开查询允许匿名；禁止按用户名查库补全缺失的 ID。 */
    public static Long getCurrentIdOrNull() {
        Authentication authentication = getAuthentication();
        if (authentication == null) {
            return null;
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            Object claim = jwt.getClaim("userId");
            if ((claim instanceof Long || claim instanceof Integer)
                    && ((Number) claim).longValue() > 0) {
                return ((Number) claim).longValue();
            }
        }
        if (authentication.getPrincipal() instanceof LoginUserDetails user) {
            return user.getUserId();
        }
        return null;
    }

    /** Token 角色用于入口权限，数据库当前权限仍由业务核对。 */
    public static boolean hasRole(String role) {
        Authentication authentication = getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> authority.getAuthority().equals("ROLE_" + role));
    }

    private static Authentication getAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication;
    }
}
