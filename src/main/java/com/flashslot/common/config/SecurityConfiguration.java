package com.flashslot.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.common.web.Result;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/** JSON 登录 + Bearer JWT；公开设备查询，业务接口保持逐请求权限检查。 */
@Configuration
public class SecurityConfiguration {
    /** 无状态鉴权只从 Authorization 头取 Token；账号密码认证由登录服务调用 AuthenticationManager。 */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, Environment environment, ObjectMapper json) throws Exception {
        http.authorizeHttpRequests(
                auth -> {
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll();
                    auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/bookings").authenticated();
                    auth.requestMatchers(
                                    HttpMethod.POST,
                                    "/api/v1/bookings/*/cancel",
                                    "/api/v1/waitlists")
                            .authenticated();
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/bookings/*/check-in")
                            .authenticated();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/admin/resources/*/check-in-code")
                            .hasRole("ADMIN");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/me/waitlists").authenticated();
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/me/waitlists/*/cancel").authenticated();
                    auth.requestMatchers("/api/v1/admin/release-jobs/**", "/api/v1/admin/messages/**", "/api/v1/admin/users/*/credit-adjustments", "/api/v1/admin/users/*/restriction-clear").hasRole("ADMIN");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/me/notifications").authenticated();
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/me/notifications/*/read").authenticated();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/admin/audits").hasRole("ADMIN");
                    auth.requestMatchers(
                                    HttpMethod.GET, "/api/v1/me/bookings", "/api/v1/bookings/*")
                            .authenticated();
                    auth.requestMatchers(
                                    HttpMethod.GET,
                                    "/api/v1/resources",
                                    "/api/v1/resources/*",
                                    "/api/v1/resources/*/schedule")
                            .permitAll();
                    auth.requestMatchers(
                                    "/api/v1/admin/resources",
                                    "/api/v1/admin/resources/*",
                                    "/api/v1/admin/resources/*/days/*")
                            .hasRole("ADMIN");
                    if (environment.acceptsProfiles(Profiles.of("local"))) {
                        auth.requestMatchers(
                                        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                                .permitAll();
                    }
                    auth.anyRequest().denyAll();
                });
        AuthenticationEntryPoint unauthenticated =
                (request, response, ex) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.setHeader("WWW-Authenticate", "Bearer");
                    json.writeValue(response.getOutputStream(), Result.error("请先登录"));
                };
        AccessDeniedHandler forbidden =
                (request, response, ex) -> {
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    json.writeValue(response.getOutputStream(), Result.error("无权执行此操作"));
                };
        http.exceptionHandling(
                errors ->
                        errors.authenticationEntryPoint(unauthenticated)
                                .accessDeniedHandler(forbidden));
        http.sessionManagement(
                session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.requestCache(AbstractHttpConfigurer::disable);
        http.formLogin(AbstractHttpConfigurer::disable);
        http.httpBasic(AbstractHttpConfigurer::disable);
        http.logout(AbstractHttpConfigurer::disable);
        // 登录不建立 Cookie 会话；Resource Server 自动豁免 Bearer 请求的 CSRF 检查。
        // 其他写请求仍保留 CSRF，未来若启用 Cookie 认证，不能沿用登录豁免假设。
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/auth/login"));
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        converter.setPrincipalClaimName("sub");
        var resolver = new DefaultBearerTokenResolver();
        resolver.setAllowFormEncodedBodyParameter(false);
        resolver.setAllowUriQueryParameter(false);
        http.oauth2ResourceServer(
                oauth ->
                        oauth.bearerTokenResolver(resolver)
                                .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                                .authenticationEntryPoint(unauthenticated)
                                .accessDeniedHandler(forbidden));
        return http.build();
    }
}
