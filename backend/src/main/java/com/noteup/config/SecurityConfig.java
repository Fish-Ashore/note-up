package com.noteup.config;

import com.noteup.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Spring Security 配置：无状态 JWT 认证、接口授权规则与 CORS。
 *
 * <p>整体策略是“读接口公开、写接口按需鉴权”：笔记、标签、分类、搜索、附件与评论查询
 * 允许匿名访问；发表评论需要登录；{@code /api/admin/**} 需要 ADMIN 角色；
 * 认证接口与外部索引钩子放行，钩子自身通过 {@code X-Hook-Token} 校验。
 * 由于令牌放在 {@code Authorization} 头而非 Cookie 中，不依赖会话，故关闭 CSRF 并
 * 使用 {@link SessionCreationPolicy#STATELESS}。</p>
 */
@Configuration
public class SecurityConfig {

    private final AppProperties properties;
    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(AppProperties properties, JwtAuthFilter jwtAuthFilter) {
        this.properties = properties;
        this.jwtAuthFilter = jwtAuthFilter;
    }

    /**
     * 构建安全过滤链：注册 JWT 过滤器、声明各路径的访问策略，并统一 401/403 的 JSON 响应。
     *
     * <p>匹配规则按声明顺序短路，因此“发表评论需登录”必须写在“评论查询公开”之后、
     * {@code anyRequest} 之前；否则要么误放开写操作，要么误伤公开读接口。</p>
     *
     * @param http Spring Security 提供的链式构建器
     * @return 配置完成的安全过滤链
     * @throws Exception 构建过滤器链失败时由 Spring Security 抛出
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 认证信息只来自请求头中的 JWT，不使用 Cookie 会话，因此不存在传统 CSRF 攻击面。
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 登录/注册由控制器自行校验凭证；钩子接口靠共享令牌鉴权。
                        .requestMatchers("/api/auth/**", "/api/hooks/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/notes/**", "/api/tags/**",
                                "/api/categories/**", "/api/search/**", "/api/assets/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/comments/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/comments").authenticated()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 其余请求默认放行；新增敏感接口时应显式补充规则，而不是依赖此兜底。
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "请先登录"))
                        .accessDeniedHandler((request, response, e) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN, "没有访问权限")))
                // JWT 过滤器需早于用户名密码过滤器执行，才能把令牌解析结果写入 SecurityContext。
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 直接输出与业务接口一致的 JSON 错误体，避免 Spring Security 默认返回空响应体。
     *
     * @param response HTTP 响应
     * @param status   HTTP 状态码
     * @param message  返回给前端的中文提示
     * @throws java.io.IOException 写入响应流失败时抛出
     */
    private static void writeError(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }

    /**
     * 提供全局密码编码器，统一使用 BCrypt（自带盐值，适合存储用户密码）。
     *
     * @return BCrypt 密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 配置跨域策略，来源白名单取自 {@code app.cors-origins}。
     *
     * <p>需要放行 {@code X-Hook-Token} 请求头，外部同步脚本才能跨域调用索引钩子；
     * 暴露 {@code Authorization} 便于前端读取服务端可能返回的令牌。启用凭证时
     * 不允许来源使用通配符，因此这里要求显式列出前端地址。</p>
     *
     * @return 基于路径匹配的 CORS 配置源
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.getCorsOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Hook-Token"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}