package com.noteup.web;

import com.noteup.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * Vault 附件访问接口，挂载在 {@code /api/assets/**} 下。
 *
 * <p>该路径在安全配置中允许匿名访问，用于展示笔记中引用的图片等非 Markdown
 * 文件。为避免泄露源文件与目录结构，接口会拒绝 Markdown 文件、点开头的隐藏
 * 文件/目录，并通过路径归一化与真实路径校验双重防御目录穿越和符号链接逃逸。</p>
 *
 * <p>错误处理：任何不存在或不安全的路径都统一返回 404，避免通过不同状态码
 * 探测 Vault 内部结构。</p>
 */
@RestController
public class AssetController {

    /** 附件 URL 前缀，同时用于截取请求路径中的相对路径。 */
    private static final String PREFIX = "/api/assets/";

    private final AppProperties properties;

    /**
     * 构造控制器。
     *
     * @param properties 应用配置，用于获取 Vault 根目录
     */
    public AssetController(AppProperties properties) {
        this.properties = properties;
    }

    /**
     * 读取并返回 Vault 内的单个附件，对应 {@code GET /api/assets/**}，允许匿名访问。
     *
     * <p>安全校验顺序：先拒绝空路径和 {@code .md} 文件（避免直接暴露 Markdown
     * 原文），再拒绝任一点开头的路径段（如 {@code .obsidian}、{@code .trash}），
     * 随后归一化路径并确认仍位于 Vault 根目录内，最后通过 {@code toRealPath}
     * 解析符号链接后再次确认未逃逸。任一步失败均按 404 处理。</p>
     *
     * <p>响应会缓存 6 小时并设置 {@code nosniff} 与 {@code CSP: sandbox}，
     * 降低附件被当作可执行内容渲染的风险；无法识别类型时按二进制流返回。</p>
     *
     * @param request 当前 HTTP 请求，用于取得原始 URI 路径
     * @return 200 与附件资源；路径非法、文件不存在或读取失败时返回 404
     */
    @GetMapping("/api/assets/**")
    public ResponseEntity<Resource> asset(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.length() <= PREFIX.length()) {
            throw ApiException.notFound("文件不存在");
        }
        // 请求 URI 可能是百分号编码的，先解码得到真实相对路径。
        String raw = URLDecoder.decode(uri.substring(PREFIX.length()), StandardCharsets.UTF_8);
        // 禁止直接下载 Markdown 源文件，避免绕过笔记的发布/权限控制。
        if (raw.isBlank() || raw.toLowerCase(Locale.ROOT).endsWith(".md")) {
            throw ApiException.notFound("文件不存在");
        }
        // 拒绝隐藏目录/文件（如 .git、.obsidian、.trash），防止泄露内部数据。
        for (String segment : raw.split("/")) {
            if (segment.startsWith(".")) {
                throw ApiException.notFound("文件不存在");
            }
        }

        // 归一化后必须仍以 Vault 根目录开头，阻止 ../ 形式的目录穿越。
        Path root = Path.of(properties.getVaultRoot()).toAbsolutePath().normalize();
        Path resolved = root.resolve(raw).normalize();
        try {
            if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
                throw ApiException.notFound("文件不存在");
            }
            // 解析符号链接后再校验一次，防止链接指向 Vault 之外。
            Path realRoot = root.toRealPath();
            Path realFile = resolved.toRealPath();
            if (!realFile.startsWith(realRoot)) {
                throw ApiException.notFound("文件不存在");
            }
            // 未知类型回退为二进制流，避免浏览器猜测内容类型。
            String contentType = Files.probeContentType(realFile);
            MediaType mediaType = contentType == null
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(contentType);
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic())
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "sandbox")
                    .body(new FileSystemResource(realFile));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.notFound("文件不存在");
        }
    }
}