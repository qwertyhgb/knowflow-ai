package io.github.qwertyhgb.knowflow.auth.config;

import io.github.qwertyhgb.knowflow.auth.filter.EnterpriseContextFilter;
import io.github.qwertyhgb.knowflow.auth.filter.TokenAuthenticationFilter;
import io.github.qwertyhgb.knowflow.auth.handler.RestAccessDeniedHandler;
import io.github.qwertyhgb.knowflow.auth.handler.RestAuthenticationEntryPoint;
import io.github.qwertyhgb.knowflow.auth.token.TokenService;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseMemberMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseRoleMapper;
import io.github.qwertyhgb.knowflow.enterprise.mapper.EnterpriseRolePermissionMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
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
 *
 * <p><strong>方法级安全（{@code @EnableMethodSecurity}）：</strong>开启后即可在
 * Controller 方法上用 SpEL 表达式声明权限要求，例如
 * {@code @PreAuthorize("hasAuthority('member:remove')")}。这些权限码由
 * {@code EnterpriseContextFilter} 在成员身份校验通过后，从角色-权限关联加载并
 * 写入 {@code Authentication.getAuthorities()}；方法执行前若权限不足，方法级安全
 * 会抛出 {@code AuthorizationDeniedException}（{@code AccessDeniedException} 的子类），
 * 由 {@code GlobalExceptionHandler} 统一转为 403。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    private final TokenService tokenService;

    private final RestAuthenticationEntryPoint authenticationEntryPoint;

    private final RestAccessDeniedHandler accessDeniedHandler;

    /** 企业上下文过滤器校验成员身份使用（企业作用域规则的数据来源）。 */
    private final EnterpriseMemberMapper enterpriseMemberMapper;

    /** 企业上下文过滤器按 roleId 解析成员角色使用（角色编码的数据来源）。 */
    private final EnterpriseRoleMapper enterpriseRoleMapper;

    /** 企业上下文过滤器注入权限码使用（角色→权限码 的数据来源）。 */
    private final EnterpriseRolePermissionMapper enterpriseRolePermissionMapper;

    /** 过滤器层的统一错误响应序列化（与 RestAuthenticationEntryPoint 同源）。 */
    private final JsonMapper jsonMapper;

    public SecurityConfig(TokenService tokenService,
                          RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler,
                          EnterpriseMemberMapper enterpriseMemberMapper,
                          EnterpriseRoleMapper enterpriseRoleMapper,
                          EnterpriseRolePermissionMapper enterpriseRolePermissionMapper,
                          JsonMapper jsonMapper) {
        this.tokenService = tokenService;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.enterpriseMemberMapper = enterpriseMemberMapper;
        this.enterpriseRoleMapper = enterpriseRoleMapper;
        this.enterpriseRolePermissionMapper = enterpriseRolePermissionMapper;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 安全过滤器链：本配置的核心。Spring Security 把「请求如何被保护」描述成一条过滤器链，
     * 这个方法用建造者（{@code http.xxx()}）逐步声明：关掉哪些默认机制、放行哪些 URL、
     * 失败怎么处理、以及把我们的两个自定义过滤器插到链的什么位置。最终 {@code http.build()}
     * 把这些规则编译成一条真正生效的过滤器链。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        TokenAuthenticationFilter tokenAuthenticationFilter = new TokenAuthenticationFilter(tokenService);
        // 企业上下文过滤器必须位于 Token 认证之后：它依赖已建立的认证主体，
        // 未认证请求直接放行走 401（先认证，再谈上下文）。
        EnterpriseContextFilter enterpriseContextFilter =
                new EnterpriseContextFilter(
                        enterpriseMemberMapper, enterpriseRoleMapper, enterpriseRolePermissionMapper, jsonMapper);
        http
                // CSRF 防护依赖「浏览器自动带上的 Cookie 凭证」来识别合法请求。
                // 本项目是无状态 REST API，用 Token 而非 Cookie 认证，没有可被跨站盗用的
                // 会话 Cookie，因此关闭 CSRF 检查（关了反而更合适，开着会误拦正常请求）。
                .csrf(csrf -> csrf.disable())
                // 无状态：Spring Security 不创建、也不依赖 HttpSession 来保存登录态。
                // 每次请求都靠请求里自带的 Token 重新认证，服务端不记「会话」。
                // 这与本项目的 Token 认证模型一致，也便于水平扩展（多实例无需共享会话）。
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 授权规则：逐条匹配，写在前面的优先；都不匹配时落到最后的 anyRequest。
                .authorizeHttpRequests(auth -> auth
                        // 注册、登录是公开接口——否则用户永远拿不到 Token，也就永远无法登录。
                        .requestMatchers("/api/users/register", "/api/users/login").permitAll()
                        // Swagger 文档相关路径也放行，方便本地调试时查看 API。
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // 其余所有请求都必须「已认证」（携带有效 Token），否则被拒绝。
                        .anyRequest().authenticated())
                // 异常处理：把认证/授权失败转成统一的 JSON 响应，区分两种失败场景。
                .exceptionHandling(exception -> exception
                        // 未认证（没带 Token 或 Token 无效）时返回 401，
                        // 由 RestAuthenticationEntryPoint 输出统一错误体。
                        .authenticationEntryPoint(authenticationEntryPoint)
                        // 已认证但权限不足（如方法上的 @PreAuthorize 不通过）时返回 403，
                        // 由 RestAccessDeniedHandler 输出统一错误体。
                        .accessDeniedHandler(accessDeniedHandler))
                // 把自定义 Token 认证过滤器插到 Spring 自带的
                // UsernamePasswordAuthenticationFilter 之前：请求先经过我们的 Token 解析与认证。
                .addFilterBefore(tokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // 企业上下文过滤器插在 Token 认证过滤器之后（顺序见下方构造处的说明）：
                // 必须先有认证主体，才能在此基础上补充企业上下文。
                .addFilterAfter(enterpriseContextFilter, TokenAuthenticationFilter.class)
                // 不使用表单登录（那套浏览器跳转 + 登录页的认证方式），本项目是 JSON API。
                .formLogin(form -> form.disable())
                // 不使用 HTTP Basic 认证（浏览器弹窗输入账号密码的方式），本项目统一用 Token。
                .httpBasic(basic -> basic.disable());
        return http.build();
    }

    /**
     * 本项目仅用 Token 认证，不提供用户名/密码的 UserDetailsService；
     * 但必须定义这个 bean，原因和「认证逻辑」无关，而是为了关掉 Spring Boot 的默认行为：
     *
     * <p>Spring Boot 的安全自动配置发现容器里「没有」UserDetailsService / AuthenticationProvider /
     * AuthenticationManager 任何一个 bean 时，会偷偷生成一个内存用户（用户名 user、随机密码并打印到控制台）。
     * 我们不想要这个默认账号，所以主动声明一个 UserDetailsService bean 来「占位」，
     * 让自动配置认为「已经有用户来源了」，从而跳过默认账号的生成。</p>
     *
     * <p>因为本项目走 Token 认证、且已关闭表单登录和 HTTP Basic，这个 bean 实际上永远不会被调用，
     * 所以实现里直接抛异常也无所谓——它存在的意义只是「存在」本身。</p>
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Token-based authentication only");
        };
    }
}
