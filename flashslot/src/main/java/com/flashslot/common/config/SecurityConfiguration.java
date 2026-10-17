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
        var metricsPeers=java.util.Arrays.stream(environment.getProperty("app.metrics.allowed-networks","127.0.0.1/32,::1/128").split(","))
            .map(String::strip).filter(s -> !s.isEmpty()).map(org.springframework.security.web.util.matcher.IpAddressMatcher::new).toList();
        http.authorizeHttpRequests(
                auth -> {
                    auth.requestMatchers(HttpMethod.GET,"/api/v1/auth/capabilities").permitAll();
                    // 指标仅允许真实连接来源网段，不信任客户端传入的转发头。
                    auth.requestMatchers("/actuator/prometheus").access((authentication,context) ->
                        new org.springframework.security.authorization.AuthorizationDecision(metricsPeers.stream()
                            .anyMatch(peer -> peer.matches(context.getRequest().getRemoteAddr()))));
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/registration/code").permitAll();
                    auth.requestMatchers("/api/v1/auth/logout", "/api/v1/me/password").authenticated();
                    auth.requestMatchers("/api/v1/admin/dead-letters", "/api/v1/admin/dead-letters/**").hasRole("ADMIN");
                    auth.requestMatchers("/api/v1/admin/users", "/api/v1/admin/users/**", "/api/v1/admin/resources/**").hasRole("ADMIN");
                    auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/bookings", "/api/v1/waitlists", "/api/v1/bookings/*/check-in").hasRole("USER");
                    auth.requestMatchers("/api/v1/me/bookings", "/api/v1/me/waitlists", "/api/v1/me/waitlists/**", "/api/v1/me/notifications", "/api/v1/me/notifications/**").hasRole("USER");
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/bookings/*/cancel").authenticated();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/admin/resources/*/check-in-code")
                            .hasRole("ADMIN");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/me/profile").authenticated();
                    auth.requestMatchers("/api/v1/admin/release-jobs/**", "/api/v1/admin/messages/**", "/api/v1/admin/users/*/credit-adjustments", "/api/v1/admin/users/*/restriction-clear").hasRole("ADMIN");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/admin/audits").hasRole("ADMIN");
                    auth.requestMatchers(
                                    HttpMethod.GET, "/api/v1/bookings/*")
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
                    json.writeValue(response.getOutputStream(), Result.error("UNAUTHENTICATED","请先登录"));
                };
        AccessDeniedHandler forbidden =
                (request, response, ex) -> {
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    json.writeValue(response.getOutputStream(), Result.error("ACCESS_DENIED","无权执行此操作"));
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
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/registration/code"));
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
