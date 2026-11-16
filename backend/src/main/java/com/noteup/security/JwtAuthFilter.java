package com.noteup.security;

import com.noteup.model.User;
import com.noteup.repo.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器：从 {@code Authorization: Bearer <token>} 中解析身份并写入安全上下文。
 *
 * <p>在过滤器链中的位置由 {@code SecurityConfig} 通过
 * {@code addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)} 指定，
 * 即位于用户名密码认证过滤器之前、授权判断之前。只有先填充
 * {@link SecurityContextHolder}，后续的 {@code authorizeHttpRequests} 与
 * {@code @PreAuthorize} 才能读到当前用户与角色。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>继承 {@link OncePerRequestFilter}，保证一次请求只执行一次，异步派发不会重复解析；</li>
 *   <li>令牌无效、过期或用户被禁用时<strong>不抛异常</strong>，而是保持匿名继续放行，
 *       由后续授权规则决定返回 401/403，使公开接口在携带坏令牌时依然可用；</li>
 *   <li>每次请求都回查数据库，让角色变更与账号禁用即时生效，代价是每请求一次查询；</li>
 *   <li>若上下文中已有认证信息则不覆盖，避免与其他认证机制冲突。</li>
 * </ul>
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    /**
     * 构造过滤器。
     *
     * @param jwtService     JWT 签发与解析服务
     * @param userRepository 用户仓储，用于按用户名加载最新账号状态
     */
    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    /**
     * 解析 Bearer 令牌，并在校验通过时建立认证上下文。
     *
     * <p>处理顺序：取请求头 → 去掉 {@code "Bearer "} 前缀（长度 7）→ 验签并取出用户名 →
     * 回查数据库确认用户存在且启用 → 组装 {@code ROLE_<role>} 权限。
     * 这里用 {@code flatMap}/{@code filter}/{@code ifPresent} 链式短路，任一步失败都会静默跳过，
     * 最终仍执行 {@code chain.doFilter}，保证请求继续流转到授权与业务层。</p>
     *
     * <p>顺带去除令牌首尾空白，兼容部分客户端在头值末尾附加空格的情况；
     * 权限前缀固定为 {@code ROLE_}，与 {@code hasRole("ADMIN")} 的匹配规则保持一致。</p>
     *
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @param chain    过滤器链
     * @throws ServletException 后续过滤器抛出的 Servlet 异常
     * @throws IOException      读写响应时的 IO 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(7).trim();
            jwtService.parseUsername(token).flatMap(userRepository::findByUsername)
                    .filter(User::enabled)
                    .ifPresent(user -> {
                        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role()));
                        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    });
        }
        chain.doFilter(request, response);
    }
}