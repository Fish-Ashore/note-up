package com.noteup.config;

import com.noteup.model.User;
import com.noteup.repo.CommentRepository;
import com.noteup.repo.NoteRepository;
import com.noteup.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 演示数据播种器：为本地体验写入示例用户与评论。
 *
 * <p>仅在 {@code app.seed-demo-data=true} 时生效，且以评论表是否为空作为幂等判据，
 * 因此重复启动不会产生重复数据。{@code @Order(200)} 保证它在启动索引（100）之后执行，
 * 此时示例笔记已入库，可以按 slug 关联评论。生产中应关闭该开关。</p>
 */
@Component
@Order(200)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    /** 所有演示用户共用的明文密码，仅用于本地登录体验。 */
    private static final String DEMO_PASSWORD = "demo123";

    private final AppProperties properties;
    private final UserRepository userRepository;
    private final NoteRepository noteRepository;
    private final CommentRepository commentRepository;
    private final PasswordEncoder passwordEncoder;

    /** 本次实际写入的评论条数，用于最终日志汇总。 */
    private int seeded;

    public DemoDataSeeder(AppProperties properties, UserRepository userRepository,
                          NoteRepository noteRepository, CommentRepository commentRepository,
                          PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.noteRepository = noteRepository;
        this.commentRepository = commentRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 写入演示用户及覆盖各种审核状态的评论树。
     *
     * <p>只要评论表已有任意数据就整体跳过，避免与真实数据或上一次播种混在一起；
     * 各条评论通过 hoursAgo 构造递增的历史时间，使列表排序看起来自然。</p>
     *
     * @param args 启动参数，此处未使用
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isSeedDemoData()) {
            return;
        }
        // 以评论表为空作为“尚未播种”的判据，同时避免重复创建演示用户。
        if (commentRepository.countAll() > 0) {
            log.debug("Demo comments skipped: comment table is not empty");
            return;
        }

        long reader = demoUser("demo_reader");
        long writer = demoUser("demo_writer");
        long ops = demoUser("demo_ops");

        // 顶层评论插入成功后才挂接回复，保证 parentId 一定有效。
        Long welcomeTop = comment("welcome", reader, null,
                "这个站点把 Obsidian 的双链保留下来了，阅读体验很顺。请问图片也是直接从 Vault 同步的吗？",
                "APPROVED", 72);
        if (welcomeTop != null) {
            comment("welcome", writer, welcomeTop,
                    "是的，附件通过 rsync 一起同步，后端用 /api/assets/ 提供访问。",
                    "APPROVED", 70);
            // PENDING 状态用于演示“待审核评论仅作者本人和管理端可见”。
            comment("welcome", ops, welcomeTop,
                    "补充一下，同步脚本在 deploy/sync-vault.sh 里。",
                    "PENDING", 50);
        }
        comment("welcome", writer, null,
                "Callout 和代码块的样式都挺清楚，准备照着搭一个自己的笔记站。",
                "APPROVED", 60);

        Long indexerTop = comment("indexer-design", reader, null,
                "全量索引在千篇以内确实够用，省掉了很多增量同步的复杂度。",
                "APPROVED", 48);
        if (indexerTop != null) {
            comment("indexer-design", ops, indexerTop,
                    "对，而且数据库可以随时重建，迁移服务器的时候特别省心。",
                    "APPROVED", 46);
        }

        comment("deploy-checklist", ops, null,
                "建议把备份脚本也加入 systemd timer，cron 有时不太容易排查。",
                "APPROVED", 40);

        comment("sqlite-fts5", reader, null,
                "中文前缀搜索这个细节很实用，之前一直以为 FTS5 只能搜英文。",
                "APPROVED", 36);

        comment("coffee-notes", writer, null,
                "1:15 的比例我也常用，浅烘豆可以再稍微细一点。",
                "APPROVED", 30);

        comment("markdown-pipeline", reader, null,
                "公式保护那段代码能在文章里展开讲讲吗？",
                "PENDING", 24);

        // REJECTED 与 DELETED 仅用于演示管理端过滤，普通读者看不到。
        comment("vue-state", writer, null,
                "这条是垃圾评论示例，应该只出现在管理后台。",
                "REJECTED", 20);

        comment("code-and-tables", ops, null,
                "代码高亮主题和正文对比度不错。",
                "APPROVED", 18);
        comment("code-and-tables", reader, null,
                "这条评论已被删除，仅用于演示 DELETED 状态。",
                "DELETED", 16);

        comment("math-demo", writer, null,
                "块级公式现在渲染正常了，之前多行 $$ 确实容易被拆散。",
                "APPROVED", 12);

        log.info("Seeded {} demo comments and 3 demo users (password: {})", seeded, DEMO_PASSWORD);
    }

    /**
     * 按用户名获取演示用户，不存在时创建一个启用的 GUEST 账号。
     *
     * @param username 演示用户名
     * @return 已存在或新创建用户的 ID
     */
    private long demoUser(String username) {
        return userRepository.findByUsername(username)
                .map(User::id)
                .orElseGet(() -> userRepository.insert(username,
                        passwordEncoder.encode(DEMO_PASSWORD), "GUEST", true, Instant.now().toString()));
    }

    /**
     * 向指定 slug 的已发布笔记插入一条演示评论。
     *
     * @param slug     目标笔记 slug
     * @param userId   评论者用户 ID
     * @param parentId 父评论 ID，{@code null} 表示顶层评论
     * @param content  评论正文
     * @param status   审核状态：PENDING / APPROVED / REJECTED / DELETED
     * @param hoursAgo 相对当前时间向前回溯的小时数，用于模拟历史发布时间
     * @return 新评论 ID；目标笔记不存在或未发布时返回 {@code null}，调用方据此跳过回复
     */
    private Long comment(String slug, long userId, Long parentId, String content,
                         String status, long hoursAgo) {
        // 只关联公开可见的笔记，避免把评论挂到草稿或已删除内容上。
        var note = noteRepository.findBySlug(slug)
                .filter(n -> n.publish() && n.deletedAt() == null);
        if (note.isEmpty()) {
            return null;
        }
        long id = commentRepository.insert(note.get().id(), userId, parentId, content, status,
                Instant.now().minus(Duration.ofHours(hoursAgo)).toString());
        seeded++;
        return id;
    }
}