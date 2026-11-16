package com.noteup.web;

import com.noteup.config.AppProperties;
import com.noteup.model.IndexResult;
import com.noteup.service.VaultIndexer;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 外部同步 Hook 接口，挂载在 {@code /api/hooks} 下。
 *
 * <p>鉴权：安全配置对 {@code /api/hooks/**} 放行匿名访问，身份校验改由
 * 请求头 {@code X-Hook-Token} 与服务端配置 {@code app.hook-token} 比对完成，
 * 便于 Vault 同步脚本或 CI 在未登录场景触发索引。</p>
 *
 * <p>错误处理：令牌缺失或不匹配统一返回 403，不区分具体原因；索引过程中的
 * 异常交由 {@link GlobalExceptionHandler} 处理（Vault 不可用时为 500）。</p>
 */
@RestController
@RequestMapping("/api/hooks")
public class HookController {

    private final VaultIndexer indexer;
    private final AppProperties properties;

    /**
     * 构造控制器。
     *
     * @param indexer    索引服务
     * @param properties 应用配置，用于读取 Hook 令牌
     */
    public HookController(VaultIndexer indexer, AppProperties properties) {
        this.indexer = indexer;
        this.properties = properties;
    }

    /**
     * 由外部 Hook 触发全量重建索引，对应 {@code POST /api/hooks/reindex}。
     *
     * <p>令牌通过 {@code MessageDigest.isEqual} 做常量时间比较，降低时序侧信道
     * 泄露风险；令牌首尾空白会被去除。配置项为空时视为校验失败，避免误将
     * 空令牌当作有效凭证。</p>
     *
     * @param token 请求头 {@code X-Hook-Token}，声明为非必填以便自行返回 403
     * @return 本次索引统计结果
     * @throws ApiException 令牌缺失或不匹配时返回 403；索引失败时返回 500
     */
    @PostMapping("/reindex")
    public IndexResult reindex(@RequestHeader(value = "X-Hook-Token", required = false) String token) {
        String expected = properties.getHookToken();
        if (token == null || expected == null || !MessageDigest.isEqual(
                token.trim().getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.forbidden("无效的 Hook Token");
        }
        return indexer.reindex();
    }
}