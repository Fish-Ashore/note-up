package com.noteup.model;

/**
 * 用户实体，对应 {@code app_user} 表中的一行。
 *
 * <p>角色仅有 {@code ADMIN} 与 {@code GUEST} 两种（数据库层有 CHECK 约束），
 * 认证时由 JWT 过滤器拼成 {@code ROLE_ + role} 的权限。{@code passwordHash} 仅在
 * 登录校验时使用，任何对外响应都应通过 {@link #toView()} 脱敏。</p>
 *
 * @param id           用户主键
 * @param username     登录名，全局唯一
 * @param passwordHash BCrypt 密码哈希，绝不返回给前端
 * @param role         角色：ADMIN / GUEST
 * @param enabled      是否启用；禁用后即使令牌有效也无法通过认证
 * @param createdAt    创建时间，ISO-8601 字符串
 */
public record User(
        long id,
        String username,
        String passwordHash,
        String role,
        boolean enabled,
        String createdAt
) {
    /**
     * 转换为可安全返回给前端的用户视图，剔除密码哈希。
     *
     * @return 不含敏感字段的用户视图
     */
    public UserView toView() {
        return new UserView(id, username, role, enabled, createdAt);
    }

    /**
     * 用户视图：仅包含展示与鉴权所需的非敏感字段。
     *
     * @param id        用户主键
     * @param username  登录名
     * @param role      角色：ADMIN / GUEST
     * @param enabled   是否启用
     * @param createdAt 创建时间
     */
    public record UserView(long id, String username, String role, boolean enabled, String createdAt) {
    }
}