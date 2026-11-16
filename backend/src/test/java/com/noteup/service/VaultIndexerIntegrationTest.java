package com.noteup.service;

import com.noteup.model.IndexResult;
import com.noteup.model.Note;
import com.noteup.repo.NoteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VaultIndexer} 的集成测试，启动完整 Spring 上下文并使用真实 SQLite。
 *
 * <p>测试通过临时目录构造一个隔离的 Vault 与数据库，覆盖两条主链路：
 * 仅索引已发布笔记并正确构建双链/标签/搜索；源文件消失时将笔记软删除。
 * 临时 Vault 中还放置了 {@code .trash} 目录，用于验证隐藏目录会被忽略。</p>
 *
 * <p>注意：测试类共享同一个静态 Vault 与数据库，第二个用例会临时删除文件，
 * 并在 {@code finally} 中恢复，以减少用例间相互影响。</p>
 */
@SpringBootTest
class VaultIndexerIntegrationTest {

    /** 测试专用 Vault 根目录，类加载时创建。 */
    private static final Path VAULT;
    /** 测试专用 SQLite 数据库文件路径。 */
    private static final Path DB;

    // 初始化测试夹具：两篇已发布笔记、一篇未发布笔记，以及应被忽略的 .trash 文件。
    static {
        try {
            VAULT = Files.createTempDirectory("noteup-vault-");
            DB = Files.createTempDirectory("noteup-db-").resolve("test.db");
            Files.writeString(VAULT.resolve("hello.md"), """
                    ---
                    title: Hello Note
                    slug: hello
                    publish: true
                    category: Test
                    tags: [one, two]
                    ---

                    # Hello

                    Link to [[Other Note]] and [[Missing Note]].

                    > [!note] Callout
                    > Body text here.
                    """);
            Files.writeString(VAULT.resolve("other.md"), """
                    ---
                    title: Other Note
                    publish: true
                    tags:
                      - two
                    ---

                    # Other

                    Back to [[Hello Note]].
                    """);
            Files.writeString(VAULT.resolve("private.md"), """
                    ---
                    title: Private
                    ---

                    # Private
                    """);
            Path trash = VAULT.resolve(".trash");
            Files.createDirectories(trash);
            Files.writeString(trash.resolve("ignored.md"), """
                    ---
                    publish: true
                    ---

                    # Ignored
                    """);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * 向 Spring 环境注入临时 Vault 与数据库配置。
     *
     * <p>关闭启动时自动索引，确保统计断言只受测试内显式调用的
     * {@code reindex()} 影响；SQLite 开启 WAL、忙等待与外键约束。</p>
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.vault-root", VAULT::toString);
        registry.add("app.index-on-startup", () -> "false");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB
                + "?journal_mode=WAL&busy_timeout=10000&foreign_keys=on");
    }

    /** 被测索引器。 */
    @Autowired
    private VaultIndexer indexer;

    /** 用于直接核对持久化结果的仓库。 */
    @Autowired
    private NoteRepository noteRepository;

    /** 用于验证标签与搜索等上层查询能力。 */
    @Autowired
    private NoteService noteService;

    /**
     * 验证索引范围与派生数据构建。
     *
     * <p>关键断言：{@code .trash} 下的文件不计入扫描；三篇 Markdown 中两篇
     * 发布、一篇未发布；渲染结果包含双链与 Callout；未发布笔记不可见；标签
     * 合并前置元数据与正文；反向链接和全文搜索可用。</p>
     */
    @Test
    void indexesOnlyPublishedNotesAndBuildsLinks() {
        IndexResult result = indexer.reindex();

        // .trash/ignored.md 应被忽略，因此只扫描 3 个文件。
        assertEquals(3, result.scanned());
        assertEquals(2, result.published());
        assertEquals(1, result.unpublished());
        assertEquals(0, result.failed());

        // 已发布笔记应完成渲染，并把 wiki 链接改写为站内地址。
        Note hello = noteRepository.findBySlug("hello").orElseThrow();
        assertTrue(hello.publish());
        assertTrue(hello.contentHtml().contains("href=\"/notes/other\""));
        assertTrue(hello.contentHtml().contains("callout callout-note"));
        // 缺少 publish: true 的笔记保持未发布状态。
        assertFalse(noteRepository.findBySlug("private").orElseThrow().publish());
        // 前置元数据中的标签应被持久化并可按顺序读回。
        assertEquals(List.of("one", "two"), noteService.detail("hello").tags());

        // other 的反向链接中应出现 hello，且全文索引可检索到 hello。
        Note other = noteRepository.findBySlug("other").orElseThrow();
        assertTrue(noteRepository.listBacklinks(other.id()).stream()
                .anyMatch(note -> note.slug().equals("hello")));
        assertFalse(noteService.search("hello", 10).isEmpty());
    }

    /**
     * 验证源文件消失后的软删除行为。
     *
     * <p>先建立基线索引，再删除 {@code other.md} 并重新索引。关键断言：
     * 删除数量至少为 1，原记录仍保留但写入 {@code deletedAt} 且取消发布。
     * {@code finally} 中恢复源文件并再次索引，避免影响其他用例。</p>
     *
     * @throws IOException 恢复测试文件失败时抛出
     */
    @Test
    void softDeletesNotesWhenSourceDisappears() throws IOException {
        indexer.reindex();
        Note other = noteRepository.findBySlug("other").orElseThrow();
        Path source = VAULT.resolve("other.md");
        Files.deleteIfExists(source);
        try {
            IndexResult result = indexer.reindex();
            // 缺失的源文件应被计入 deleted，而不是从数据库物理删除。
            assertTrue(result.deleted() >= 1);
            Note deleted = noteRepository.findById(other.id()).orElseThrow();
            assertNotNull(deleted.deletedAt());
            assertFalse(deleted.publish());
        } finally {
            // 恢复夹具并重建索引，保证后续用例看到的仍是完整 Vault。
            Files.writeString(source, """
                    ---
                    title: Other Note
                    publish: true
                    tags:
                      - two
                    ---

                    # Other

                    Back to [[Hello Note]].
                    """);
            indexer.reindex();
        }
    }
}