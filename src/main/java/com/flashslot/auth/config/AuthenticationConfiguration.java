package com.flashslot.auth.config;

import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.auth.model.LoginUserDetails;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

/** Spring Security 负责账号密码认证，业务层继续承担当前权限与预约资格校验。 */
@Configuration
public class AuthenticationConfiguration {
    /** 使用 BCrypt cost=12，与受控初始化保存的 BCrypt 哈希兼容。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /** 仅认证查询读取密码哈希；以数据库规范用户名建立身份，不接受请求中的角色。 */
    @Bean
    public UserDetailsService userDetailsService(AccountPermissionMapper accounts) {
        return username -> {
            var account = accounts.findForLogin(username);
            if (account == null) throw new UsernameNotFoundException("Invalid credentials");
            boolean formal = "USER".equals(account.getRole()) || "ADMIN".equals(account.getRole());
            return new LoginUserDetails(
                    account.getId(),
                    account.getUsername(),
                    account.getPasswordHash(),
                    Boolean.TRUE.equals(account.getEnabled()) && formal,
                    List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole())));
        };
    }

    /** 认证成功后 ProviderManager 擦除认证对象中的密码，未知用户使用框架的虚拟哈希校验。 */
    @Bean
    public AuthenticationManager authenticationManager(
            UserDetailsService users, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwords);
        provider.setHideUserNotFoundExceptions(true);
        return new ProviderManager(List.of(provider));
    }
}
