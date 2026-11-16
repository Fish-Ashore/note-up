package com.noteup.web;

import com.noteup.model.NoteDetail;
import com.noteup.model.NoteSummary;
import com.noteup.model.SearchHit;
import com.noteup.model.TagCount;
import com.noteup.service.NoteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公开笔记读取接口，挂载在 {@code /api} 下。
 *
 * <p>鉴权：{@code /api/notes/**}、{@code /api/tags/**}、{@code /api/categories/**}
 * 与 {@code /api/search/**} 在安全配置中均允许匿名访问，因此本类只暴露已发布
 * 且未删除的笔记内容。</p>
 *
 * <p>错误处理：笔记不存在、未发布或已软删除时统一返回 404；参数越界由服务层
 * 归一化处理而非报错，异常最终由 {@link GlobalExceptionHandler} 渲染。</p>
 */
@RestController
@RequestMapping("/api")
public class NoteController {

    private final NoteService noteService;

    /**
     * 构造控制器。
     *
     * @param noteService 笔记查询服务
     */
    public NoteController(NoteService noteService) {
        this.noteService = noteService;
    }

    /**
     * 分页查询已发布笔记，对应 {@code GET /api/notes}，允许匿名访问。
     *
     * <p>过滤条件之间为 AND 关系；分页参数由服务层兜底：页码负数归零，
     * 每页条数限制在 1-100，因此传入极端值不会导致全表扫描或负数偏移。</p>
     *
     * @param category 可选分类精确过滤
     * @param tag      可选标签过滤
     * @param q        可选关键词，用于标题/正文搜索
     * @param page     页码，从 0 开始，默认 0
     * @param size     每页条数，默认 12，实际范围 1-100
     * @return 当前页摘要及满足条件的总条数
     */
    @GetMapping("/notes")
    public PageResponse<NoteSummary> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        return noteService.list(category, tag, q, page, size);
    }

    /**
     * 获取单篇笔记详情，对应 {@code GET /api/notes/{slug}}，允许匿名访问。
     *
     * @param slug 笔记的 URL 标识
     * @return 渲染后的 HTML、标签、时间信息与反向链接
     * @throws ApiException 笔记不存在、未发布或已删除时返回 404
     */
    @GetMapping("/notes/{slug}")
    public NoteDetail detail(@PathVariable String slug) {
        return noteService.detail(slug);
    }

    /**
     * 获取所有标签及引用次数，对应 {@code GET /api/tags}，允许匿名访问。
     *
     * @return 标签计数列表
     */
    @GetMapping("/tags")
    public List<TagCount> tags() {
        return noteService.tags();
    }

    /**
     * 获取所有已发布笔记的分类，对应 {@code GET /api/categories}，允许匿名访问。
     *
     * @return 去重后的分类名称列表
     */
    @GetMapping("/categories")
    public List<String> categories() {
        return noteService.categories();
    }

    /**
     * 全文搜索笔记，对应 {@code GET /api/search}，允许匿名访问。
     *
     * <p>优先走 SQLite FTS 全文索引，查询异常时降级为 {@code LIKE} 模糊匹配；
     * 结果条数限制在 1-50，默认 30。空白关键词直接返回空列表。</p>
     *
     * @param q     搜索关键词，多个词按 AND 组合
     * @param limit 最大返回条数，默认 30，实际范围 1-50
     * @return 命中的笔记摘要与高亮片段
     */
    @GetMapping("/search")
    public List<SearchHit> search(@RequestParam String q,
                                  @RequestParam(defaultValue = "30") int limit) {
        return noteService.search(q, limit);
    }
}