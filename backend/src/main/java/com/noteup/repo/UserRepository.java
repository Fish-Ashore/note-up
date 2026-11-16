package com.noteup.repo;

import com.noteup.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户数据访问层，对应 {@code app_user} 表，是认证流程的数据来源。
 *
 * <p>角色仅有 ADMIN 与 GUEST 两种（由数据库 CHECK 约束保证），密码以 BCrypt 哈希存储，
 * 仓储层不接触明文。{@code enabled} 是账号级开关：JWT 校验通过后仍需检查该标记，
 * 因此禁用账号无需等待令牌过期即可立即失效。</p>
 */
@Repository
public class UserRepository {

    /**
     * 行映射器：SQLite 以整数 0/1 存储 {@code enabled}，在此转换为布尔值。
     * 注意 {@code password_hash} 会随实体返回，调用方不得将其序列化到接口响应。
     */
    private static final RowMapper<User> MAPPER = (rs, rowNum) -> new User(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("role"),
            rs.getInt("enabled") != 0,
            rs.getString("created_at")
    );

    private final JdbcTemplate jdbc;

    /**
     * 构造仓储并注入 JDBC 模板。
     *
     * @param jdbc 已配置 SQLite 数据源的 JDBC 模板
     */
    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按用户名查询用户，供登录认证使用。
     *
     * @param username 登录名
     * @return 命中时返回用户（含密码哈希），否则为空 Optional
     */
    public Optional<User> findByUsername(String username) {
        List<User> users = jdbc.query("SELECT * FROM app_user WHERE username = ?", MAPPER, username);
        return users.isEmpty() ? Optional.empty() : Optional.of(users.getFirst());
    }

    /**
     * 按主键查询用户。
     *
     * @param id 用户主键
     * @return 命中时返回用户，否则为空 Optional
     */
    public Optional<User> findById(long id) {
        List<User> users = jdbc.query("SELECT * FROM app_user WHERE id = ?", MAPPER, id);
        return users.isEmpty() ? Optional.empty() : Optional.of(users.getFirst());
    }

    /**
     * 判断用户名是否已被占用，用于注册前校验。
     *
     * @param username 登录名
     * @return 已存在返回 {@code true}
     */
    public boolean existsByUsername(String username) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = ?", Long.class, username);
        return count != null && count > 0;
    }

    /**
     * 新增用户并返回主键。
     *
     * <p>用户名的唯一性由数据库约束保证；并发注册时可能抛出数据访问异常，
     * 需由上层转换为冲突响应，因此这里的“先查后插”只用于优化提示，不能作为唯一性依据。</p>
     *
     * @param username     登录名
     * @param passwordHash 已经过 BCrypt 编码的密码哈希
     * @param role         角色，取值 ADMIN 或 GUEST
     * @param enabled      是否启用
     * @param createdAt    创建时间字符串
     * @return 新用户主键
     * @throws IllegalStateException 数据库未返回主键时抛出
     */
    public long insert(String username, String passwordHash, String role, boolean enabled, String createdAt) {
        Long id = jdbc.queryForObject("INSERT INTO app_user (username, password_hash, role, enabled, created_at) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, username, passwordHash, role, enabled ? 1 : 0, createdAt);
        if (id == null) {
            throw new IllegalStateException("Failed to insert user " + username);
        }
        return id;
    }

    /**
     * 列出全部用户，按主键升序，供后台用户管理使用。
     *
     * @return 用户列表（含密码哈希，仅限服务端内部使用）
     */
    public List<User> listAll() {
        return jdbc.query("SELECT * FROM app_user ORDER BY id", MAPPER);
    }

    /**
     * 更新用户角色。
     *
     * @param id   用户主键
     * @param role 目标角色
     */
    public void updateRole(long id, String role) {
        jdbc.update("UPDATE app_user SET role = ? WHERE id = ?", role, id);
    }

    /**
     * 启用或禁用账号。禁用后，该用户已签发的令牌会在下一次请求时被过滤器拒绝。
     *
     * @param id      用户主键
     * @param enabled 是否启用
     */
    public void updateEnabled(long id, boolean enabled) {
        jdbc.update("UPDATE app_user SET enabled = ? WHERE id = ?", enabled ? 1 : 0, id);
    }

    /**
     * 统计用户总数。
     *
     * @return 用户数
     */
    public long countUsers() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * 统计启用状态的管理员数量，用于防止把最后一名管理员降级或禁用。
     *
     * @return 启用中的管理员数
     */
    public long countAdmins() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE role = 'ADMIN' AND enabled = 1", Long.class);
        return count == null ? 0 : count;
    }
}