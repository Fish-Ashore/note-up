package com.noteup.security;

import com.noteup.config.AppProperties;
import com.noteup.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * JWT 签发与解析服务，为无状态认证提供令牌。
 *
 * <p>采用 HMAC 对称签名，密钥来自 {@code app.jwt.secret}；令牌载荷包含
 * {@code sub}（用户名）、{@code uid}、{@code role} 以及签发与过期时间。
 * 由于角色和账号状态可能在令牌有效期内变化，{@link JwtAuthFilter} 会以用户名回查数据库，
 * 因此令牌中的 {@code role} 仅作辅助信息，不作为最终授权依据。</p>
 *
 * <p>服务本身不保存任何会话状态，水平扩展时只需保证各实例密钥一致。</p>
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMillis;

    /**
     * 构造服务并预计算签名密钥与有效期。
     *
     * <p>配置以小时为单位，这里换算为毫秒，避免每次签发都做单位转换。</p>
     *
     * @param properties 应用配置，读取 {@code app.jwt.secret} 与 {@code app.jwt.expiration-hours}
     */
    public JwtService(AppProperties properties) {
        this.key = Keys.hmacShaKeyFor(normalizeSecret(properties.getJwt().getSecret()));
        this.expirationMillis = properties.getJwt().getExpirationHours() * 3600_000L;
    }

    /**
     * 为指定用户签发令牌。
     *
     * <p>用户名写入标准 {@code sub}，并冗余 {@code uid}/{@code role} 便于前端展示；
     * {@code signWith(key)} 会让 jjwt 依据密钥长度自动选择 HS256/HS384 等算法，
     * 因此密钥规范化必须保证长度达标。</p>
     *
     * @param user 已通过认证的用户
     * @return 紧凑格式的 JWT 字符串
     */
    public String issue(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.username())
                .claim("uid", user.id())
                .claim("role", user.role())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMillis)))
                .signWith(key)
                .compact();
    }

    /**
     * 校验签名与有效期，并取出用户名。
     *
     * <p>所有异常都被统一吞掉并返回空 Optional：签名错误、格式非法、令牌过期等都视为“未认证”，
     * 交由过滤器按匿名请求处理，避免把解析细节暴露给客户端，也避免日志被攻击者刷屏。</p>
     *
     * @param token 不含 {@code Bearer } 前缀的 JWT
     * @return 用户名；令牌无效或缺少 subject 时返回空 Optional
     */
    public Optional<String> parseUsername(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.ofNullable(claims.getSubject());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 把配置中的密钥规范化为满足 HMAC 要求的字节数组。
     *
     * <p>HS256 至少需要 256 位（32 字节）密钥，否则 jjwt 会拒绝签名。为兼容开发环境中的
     * 简短配置，这里对不足 32 字节的密钥做一次 SHA-256 派生，既保证长度，也避免直接拒绝启动。
     * 生产环境仍应配置足够长且随机的密钥；派生只解决长度问题，不会提升低熵密钥的安全性。</p>
     *
     * @param secret 原始密钥文本
     * @return 长度不小于 32 字节的密钥字节
     * @throws IllegalStateException 运行环境不支持 SHA-256 时抛出（正常 JDK 不会发生）
     */
    private static byte[] normalizeSecret(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length >= 32) {
            return bytes;
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to derive JWT key", e);
        }
    }
}