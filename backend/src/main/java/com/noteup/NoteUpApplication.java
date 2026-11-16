package com.noteup;

import com.noteup.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Note Up 后端应用的启动入口。
 *
 * <p>该服务把 Obsidian Vault 中的 Markdown 笔记索引到 SQLite，并通过 REST API
 * 向前端提供笔记浏览、全文搜索、标签/分类与评论能力。</p>
 *
 * <p>这里显式排除 {@link UserDetailsServiceAutoConfiguration}：认证完全由自定义的
 * JWT 过滤器处理，若保留自动配置，Spring Boot 会额外生成一个默认用户并打印随机密码，
 * 既无用又容易造成误解。</p>
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(AppProperties.class)
public class NoteUpApplication {

    /**
     * 应用主入口：先确保 SQLite 数据文件所在目录存在，再启动 Spring 容器。
     *
     * @param args 命令行参数，原样交给 Spring Boot 解析
     */
    public static void main(String[] args) {
        ensureDatabaseDirectory();
        SpringApplication.run(NoteUpApplication.class, args);
    }

    /**
     * 根据 {@code DB_PATH} 环境变量（默认 {@code ./data/notes.db}）预先创建数据库父目录。
     *
     * <p>SQLite 不会自动创建不存在的目录，若等到建立连接时才处理，首次启动会直接失败；
     * 因此提前在 Spring 启动前执行，并附带路径信息以便排查。</p>
     *
     * @throws IllegalStateException 目录创建失败时抛出，阻止应用以不可用状态启动
     */
    private static void ensureDatabaseDirectory() {
        String dbPath = System.getenv().getOrDefault("DB_PATH", "./data/notes.db");
        try {
            Path parent = Path.of(dbPath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create database directory for " + dbPath, e);
        }
    }
}