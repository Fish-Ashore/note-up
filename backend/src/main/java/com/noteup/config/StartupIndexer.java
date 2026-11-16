package com.noteup.config;

import com.noteup.service.VaultIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动索引器：应用就绪后按配置触发一次 Vault 全量重建索引。
 *
 * <p>实现 {@link ApplicationRunner} 而非监听 ContextRefreshed 事件，是为了在所有
 * Bean（数据库、渲染器）初始化完成之后执行。{@code @Order(100)} 使其位于管理员播种
 * （50）之后、演示数据（200）之前，保证后续播种能读到最新的笔记数据。</p>
 */
@Component
@Order(100)
public class StartupIndexer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupIndexer.class);

    private final AppProperties properties;
    private final VaultIndexer indexer;

    public StartupIndexer(AppProperties properties, VaultIndexer indexer) {
        this.properties = properties;
        this.indexer = indexer;
    }

    /**
     * 按需执行全量索引。
     *
     * <p>索引失败只记录告警而不阻止启动：Vault 可能尚未挂载或路径配置有误，
     * 此时站点仍可启动并通过管理端/钩子重新索引，比直接启动失败更易恢复。</p>
     *
     * @param args 启动参数，此处未使用
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isIndexOnStartup()) {
            return;
        }
        try {
            indexer.reindex();
        } catch (Exception e) {
            log.warn("Startup indexing skipped: {}", e.getMessage());
        }
    }
}