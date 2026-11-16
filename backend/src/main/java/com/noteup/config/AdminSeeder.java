package com.noteup.config;

import com.noteup.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 管理员账号播种器：首次启动时按配置创建 ADMIN 账号。
 *
 * <p>使用 {@link ApplicationRunner} 在容器就绪后执行，并通过 {@code @Order(50)} 排在
 * 启动索引（100）之前。创建逻辑是幂等的：只要用户名已存在就直接返回，不会覆盖
 * 已有密码或角色，从而避免每次重启重置管理员凭证。</p>
 */
@Component
@Order(50)
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final AppProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminSeeder(AppProperties properties, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 若配置的管理员用户名尚不存在，则创建启用的 ADMIN 账号。
     *
     * <p>默认密码较弱，因此创建后打印 WARN 日志提醒部署者修改 {@code ADMIN_PASSWORD}，
     * 避免站点以公开的默认凭证上线。</p>
     *
     * @param args 启动参数，此处未使用
     */
    @Override
    public void run(ApplicationArguments args) {
        String username = properties.getAdmin().getUsername();
        // 已存在时保持原账号不动，包括其密码、角色与启用状态。
        if (userRepository.existsByUsername(username)) {
            return;
        }
        userRepository.insert(username,
                // 只存 BCrypt 哈希，配置中的明文密码不会落库。
                passwordEncoder.encode(properties.getAdmin().getPassword()),
                "ADMIN", true, Instant.now().toString());
        log.warn("Seeded ADMIN account '{}'. Change ADMIN_PASSWORD before exposing the site.", username);
    }
}