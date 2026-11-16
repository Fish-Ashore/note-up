package com.noteup.service;

import com.noteup.model.User;
import com.noteup.repo.UserRepository;
import com.noteup.security.JwtService;
import com.noteup.web.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 认证服务：处理注册、登录并签发 JWT。
 *
 * <p>应用采用无状态认证：登录成功后由 {@link JwtService} 签发包含用户 id、角色与过期时间的令牌，
 * 服务端不保存会话；后续请求由安全过滤器验签并加载用户。密码只以哈希形式落库，校验交给
 * Spring Security 的 {@link PasswordEncoder}（BCrypt），因此本类看不到也无法还原明文密码。</p>
 *
 * <p>注册一律创建启用状态的 {@code GUEST} 账号，管理员只能由启动时的 {@code AdminSeeder}
 * 播种或由现有管理员提升，避免开放注册成为提权入口。用户名与密码的格式约束由控制器的
 * Bean Validation 负责，本类只处理业务层冲突与凭据校验，并在比较前对用户名做裁剪。</p>
 *
 * <p>安全细节：用户不存在与密码错误返回同一条消息，避免通过错误文案枚举已注册用户名；
 * 账号被禁用则单独返回 403，让合法用户知道需要联系管理员。</p>
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * 构造认证服务。
     *
     * @param userRepository  用户持久化
     * @param passwordEncoder 密码哈希与校验
     * @param jwtService      令牌签发
     */
    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * 注册新用户并直接签发令牌，减少一次登录往返。
     *
     * <p>先查重再插入，常规情况下能给出友好的“用户名已被占用”；两步之间仍存在并发窗口，
     * 最终由数据库的 {@code username} 唯一约束兜底。注册后重新读取用户，保证返回的视图包含
     * 数据库生成的 id 与默认字段。</p>
     *
     * @param username 用户名，仅去除首尾空白，格式由控制层校验
     * @param password 明文密码，落库前经 BCrypt 哈希
     * @return 新签发的令牌与用户视图
     * @throws ApiException 用户名已存在时抛出 409
     */
    public AuthResponse register(String username, String password) {
        String name = username.trim();
        if (userRepository.existsByUsername(name)) {
            throw ApiException.conflict("用户名已被占用");
        }
        // 角色固定为 GUEST 且默认启用；提升权限只能走管理端，避免注册接口成为提权通道。
        long id = userRepository.insert(name, passwordEncoder.encode(password), "GUEST", true,
                Instant.now().toString());
        User user = userRepository.findById(id).orElseThrow();
        return new AuthResponse(jwtService.issue(user), user.toView());
    }

    /**
     * 校验用户名密码并签发令牌。
     *
     * <p>先查用户再校验密码，使“用户不存在”和“密码错误”对外表现一致；禁用检查放在密码校验之前，
     * 这样被禁用的用户即使密码正确也会得到明确的 403，而不会误以为是输错密码。</p>
     *
     * @param username 用户名，比较前去除首尾空白
     * @param password 明文密码
     * @return 新签发的令牌与用户视图
     * @throws ApiException 用户不存在或密码错误时抛出 401，账号禁用时抛出 403
     */
    public AuthResponse login(String username, String password) {
        User user = userRepository.findByUsername(username.trim())
                .orElseThrow(() -> ApiException.unauthorized("用户名或密码错误"));
        if (!user.enabled()) {
            throw ApiException.forbidden("账号已被禁用");
        }
        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw ApiException.unauthorized("用户名或密码错误");
        }
        return new AuthResponse(jwtService.issue(user), user.toView());
    }

    /**
     * 登录/注册的统一响应。
     *
     * @param token JWT，客户端后续通过 Authorization 头携带
     * @param user  当前用户的安全视图，不含密码哈希
     */
    public record AuthResponse(String token, User.UserView user) {
    }
}