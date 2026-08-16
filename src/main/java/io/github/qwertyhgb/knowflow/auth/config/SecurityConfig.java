package io.github.qwertyhgb.knowflow.auth.config;

import io.github.qwertyhgb.knowflow.auth.filter.EnterpriseContextFilter;
import io.github.qwertyhgb.knowflow.auth.filter.TokenAuthenticationFilter;
import io.github.qwertyhgb.knowflow.auth.handler.RestAccessDeniedHandler;
import io.github.qwertyhgb.knowflow.auth.handler.RestAuthenticationEntryPoint;
import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Security 安全配置。
 *
 * <p>无状态 REST API：关闭 CSRF 与表单/基础认证，注册与登录放行，
 * 其余请求需依次通过过滤器链中的两个自定义过滤器：</p>
 * <ol>
 *   <li>{@link TokenAuthenticationFilter} —— 解析 Bearer Token 建立认证
 *       （主体为无企业上下文的 {@code EnterpriseUser}）；</li>
 *   <li>{@link EnterpriseContextFilter} —— 对企业作用域路径
 *       （{@code /api/enterprises/{enterpriseId}} 及其子路径）强制校验
 *       {@code X-Enterprise-Id} 请求头并注入当前企业上下文。</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private final TokenService tokenService;

    private final RestAuthenticationEntryPoint authenticationEntryPoint;

    private final RestAccessDeniedHandler accessDeniedHandler;

    /** 企业上下文过滤器校验成员身份使用（企业作用域规则的数据来源）。 */
    private final EnterpriseMemberMapper enterpriseMemberMapper;

    /** 过滤器层的统一错误响应序列化（与 RestAuthenticationEntryPoint 同源）。 */
    private final JsonMapper jsonMapper;

    public SecurityConfig(TokenService tokenService,
                          RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler,
                          EnterpriseMemberMapper enterpriseMemberMapper,
                          JsonMapper jsonMapper) {
        this.tokenService = tokenService;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.enterpriseMemberMapper = enterpriseMemberMapper;
        this.jsonMapper = jsonMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        TokenAuthenticationFilter tokenAuthenticationFilter = new TokenAuthenticationFilter(tokenService);
        // 企业上下文过滤器必须位于 Token 认证之后：它依赖已建立的认证主体，
        // 未认证请求直接放行走 401（先认证，再谈上下文）。
        EnterpriseContextFilter enterpriseContextFilter =
                new EnterpriseContextFilter(enterpriseMemberMapper, jsonMapper);
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
                .addFilterAfter(enterpriseContextFilter, TokenAuthenticationFilter.class)
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
