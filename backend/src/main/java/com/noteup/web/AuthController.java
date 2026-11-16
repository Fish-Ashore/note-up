package com.noteup.web;

import com.noteup.model.User;
import com.noteup.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口，统一挂载在 {@code /api/auth} 下。
 *
 * <p>鉴权：注册与登录允许匿名访问；{@code /me} 依赖请求头中的 Bearer JWT，
 * 由 {@code JwtAuthFilter} 解析用户并注入 {@code @AuthenticationPrincipal}。
 * 由于安全配置放行了整个 {@code /api/auth/**}，{@code /me} 需要自行判空并
 * 返回 401。</p>
 *
 * <p>错误处理：参数校验失败由 {@link GlobalExceptionHandler} 转为 400；
 * 用户名重复返回 409；凭证错误返回 401；账号被禁用返回 403。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    /**
     * 构造控制器。
     *
     * @param authService 认证服务
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 注册新用户，对应 {@code POST /api/auth/register}，允许匿名访问。
     *
     * <p>新用户默认角色为 {@code GUEST} 且处于启用状态；注册成功后立即签发 JWT，
     * 便于前端直接进入登录态。</p>
     *
     * @param request 用户名与密码，已通过 Bean Validation 校验
     * @return JWT 令牌与用户信息
     * @throws ApiException 用户名已存在时返回 409
     */
    @PostMapping("/register")
    public AuthService.AuthResponse register(@Valid @RequestBody AuthRequest request) {
        return authService.register(request.username(), request.password());
    }

    /**
     * 登录，对应 {@code POST /api/auth/login}，允许匿名访问。
     *
     * <p>用户不存在或密码错误统一返回 401，避免暴露用户名是否存在；
     * 已禁用账号返回 403。</p>
     *
     * @param request 用户名与密码
     * @return JWT 令牌与用户信息
     * @throws ApiException 凭证错误返回 401，账号被禁用返回 403
     */
    @PostMapping("/login")
    public AuthService.AuthResponse login(@Valid @RequestBody AuthRequest request) {
        return authService.login(request.username(), request.password());
    }

    /**
     * 获取当前登录用户，对应 {@code GET /api/auth/me}，需要携带有效 JWT。
     *
     * @param user 由安全过滤器注入的当前用户，未登录时为 null
     * @return 当前用户视图（不含密码哈希）
     * @throws ApiException 未登录或令牌无效时返回 401
     */
    @GetMapping("/me")
    public User.UserView me(@AuthenticationPrincipal User user) {
        if (user == null) {
            throw ApiException.unauthorized("请先登录");
        }
        return user.toView();
    }

    /**
     * 注册/登录请求体。用户名与密码同时适用于两个接口，校验规则如下：
     * 用户名 3-32 位，仅允许字母、数字、下划线和短横线；密码 6-72 个字符
     * （72 对应 BCrypt 的输入长度上限，此处的长度校验按字符数计算）。
     *
     * @param username 用户名
     * @param password 明文密码，服务端使用 BCrypt 哈希后存储
     */
    public record AuthRequest(
            @NotBlank(message = "请输入用户名")
            @Size(min = 3, max = 32, message = "用户名长度需为 3-32 个字符")
            @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "用户名仅支持字母、数字、下划线和短横线")
            String username,
            @NotBlank(message = "请输入密码")
            @Size(min = 6, max = 72, message = "密码长度需为 6-72 个字符")
            String password
    ) {
    }
}