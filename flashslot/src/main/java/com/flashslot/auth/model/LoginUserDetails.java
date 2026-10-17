package com.flashslot.auth.model;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

/** 密码认证后的用户身份，密码由 Spring Security 在认证成功后擦除。 */
public class LoginUserDetails extends User {
    private final Long userId;
    private long credentialVersion;

    public long getCredentialVersion() { return credentialVersion; }
    public void setCredentialVersion(long version) { credentialVersion=version; }

    public LoginUserDetails(
            Long userId,
            String username,
            String password,
            boolean enabled,
            Collection<? extends GrantedAuthority> authorities) {
        super(username, password, enabled, true, true, true, authorities);
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }
}
