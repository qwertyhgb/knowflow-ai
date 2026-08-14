package io.github.qwertyhgb.knowflow.auth.config;

import io.github.qwertyhgb.knowflow.auth.filter.TokenAuthenticationFilter;
import io.github.qwertyhgb.knowflow.auth.handler.RestAccessDeniedHandler;
import io.github.qwertyhgb.knowflow.auth.handler.RestAuthenticationEntryPoint;
import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 安全配置。
 *
 * <p>无状态 REST API：关闭 CSRF 与表单/基础认证，注册与登录放行，
 * 其余请求需通过 {@link TokenAuthenticationFilter} 认证。</p>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private final TokenService tokenService;

    private final RestAuthenticationEntryPoint authenticationEntryPoint;

    private final RestAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(TokenService tokenService,
                          RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler) {
        this.tokenService = tokenService;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        TokenAuthenticationFilter tokenAuthenticationFilter = new TokenAuthenticationFilter(tokenService);
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/users/register", "/api/users/login").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(tokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());
        return http.build();
    }

    /**
     * 本项目仅用 Token 认证，不提供用户名/密码的 UserDetailsService；
     * 定义此 bean 仅为阻止 Spring Boot 生成默认内存用户与随机密码。
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Token-based authentication only");
        };
    }
}
