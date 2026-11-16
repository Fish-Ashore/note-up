package com.noteup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 应用级配置，绑定 {@code application.yml} 中以 {@code app} 为前缀的属性。
 *
 * <p>集中承载 Vault 路径、索引开关、跨域来源、JWT、管理员账号与评论限流等可调参数，
 * 使业务组件无需散落读取 {@code @Value}。所有字段都带有适合本地开发的默认值，
 * 生产环境应通过环境变量或外部配置覆盖敏感项（{@code hookToken}、{@code jwt.secret}、
 * {@code admin.password}）。</p>
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Obsidian Vault 根目录；索引器与附件接口都以此为基准，默认指向仓库自带的示例 Vault。 */
    private String vaultRoot = "../sample-vault";
    /** 外部重新索引钩子（{@code /api/hooks/**}）的共享令牌，生产环境必须覆盖。 */
    private String hookToken = "dev-hook-token";
    /** 是否在应用启动后自动全量重建索引；关闭时需通过管理端或钩子手动触发。 */
    private boolean indexOnStartup = true;
    /** 是否写入演示评论与演示用户；仅用于本地体验，生产环境应保持关闭。 */
    private boolean seedDemoData = false;
    /** 允许跨域访问的前端来源，默认是 Vite 开发服务器地址。 */
    private List<String> corsOrigins = List.of("http://localhost:5173");
    /** JWT 签发与校验配置。 */
    private Jwt jwt = new Jwt();
    /** 初始管理员账号配置，仅在数据库中不存在该用户名时用于播种。 */
    private Admin admin = new Admin();
    /** 评论频率与长度限制。 */
    private Comments comments = new Comments();

    public String getVaultRoot() {
        return vaultRoot;
    }

    public void setVaultRoot(String vaultRoot) {
        this.vaultRoot = vaultRoot;
    }

    public String getHookToken() {
        return hookToken;
    }

    public void setHookToken(String hookToken) {
        this.hookToken = hookToken;
    }

    public boolean isIndexOnStartup() {
        return indexOnStartup;
    }

    public void setIndexOnStartup(boolean indexOnStartup) {
        this.indexOnStartup = indexOnStartup;
    }

    public boolean isSeedDemoData() {
        return seedDemoData;
    }

    public void setSeedDemoData(boolean seedDemoData) {
        this.seedDemoData = seedDemoData;
    }

    public List<String> getCorsOrigins() {
        return corsOrigins;
    }

    public void setCorsOrigins(List<String> corsOrigins) {
        this.corsOrigins = corsOrigins;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    public Comments getComments() {
        return comments;
    }

    public void setComments(Comments comments) {
        this.comments = comments;
    }

    /**
     * JWT 签发与校验配置。
     *
     * <p>默认密钥仅用于本地开发；上线前必须通过 {@code app.jwt.secret} 覆盖，
     * 否则任何人都能用这份公开的默认密钥伪造令牌。</p>
     */
    public static class Jwt {
        /** HMAC 签名密钥，长度需足够且在生产环境保密。 */
        private String secret = "note-up-dev-secret-change-me-in-production-0123456789";
        /** 令牌有效期（小时），默认 168 小时即 7 天；过期后需重新登录。 */
        private long expirationHours = 168;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public long getExpirationHours() {
            return expirationHours;
        }

        public void setExpirationHours(long expirationHours) {
            this.expirationHours = expirationHours;
        }
    }

    /**
     * 初始管理员账号配置。
     *
     * <p>仅在启动时数据库中不存在该用户名的情况下创建，已存在则跳过，
     * 因此修改这里的密码不会重置既有账号。</p>
     */
    public static class Admin {
        /** 管理员用户名。 */
        private String username = "admin";
        /** 初始密码，使用 BCrypt 加密后入库；默认值仅供本地开发。 */
        private String password = "admin123";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /**
     * 评论发布限制，用于防止刷屏与超长内容。
     *
     * <p>限流按用户维度统计（由服务层实现），这里的值只是上限阈值。</p>
     */
    public static class Comments {
        /** 单用户每分钟最多可发布的评论数。 */
        private int perMinute = 3;
        /** 单用户每天最多可发布的评论数。 */
        private int perDay = 50;
        /** 单条评论正文允许的最大字符数。 */
        private int maxLength = 2000;

        public int getPerMinute() {
            return perMinute;
        }

        public void setPerMinute(int perMinute) {
            this.perMinute = perMinute;
        }

        public int getPerDay() {
            return perDay;
        }

        public void setPerDay(int perDay) {
            this.perDay = perDay;
        }

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(int maxLength) {
            this.maxLength = maxLength;
        }
    }
}