# Obsidian 笔记发布系统设计方案

## 一、项目概述

### 1.1 目标
将本地 Obsidian 笔记通过互联网展示，支持公开阅读、登录评论、管理员管理。笔记源文件保持 Obsidian 原生格式，不做侵入式改造。

### 1.2 角色
| 角色 | 权限 |
|---|---|
| 管理员（ADMIN） | 全部权限：笔记可见性、评论审核、用户管理、触发重索引 |
| 游客（GUEST） | 阅读已发布笔记、登录后发表评论 |
| 未登录访客 | 仅阅读已发布笔记 |

### 1.3 规模假设
当前约 100 篇笔记，设计上按 **千篇以内** 规划，**不做过度扩展**（不上 MQ、不上 ES、不上分布式缓存）。

---

## 二、整体架构

```
Obsidian Vault（本地）
   │  rsync 同步（排除 .obsidian/.trash，仅同步 publish:true）
   ▼
服务器 /srv/vault
   │  rsync 完成后 SSH 触发 hook（cron 兜底）
   ▼
Spring Boot 后端
   ├─ VaultIndexer      扫描 + 解析 frontmatter + 渲染 Markdown → 写 DB
   ├─ 公开 API          笔记列表 / 详情 / 搜索 / 标签
   ├─ 认证 API          注册 / 登录 / JWT
   ├─ 评论 API          发表 / 查询（审核后展示）
   └─ 管理 API          重索引 / 评论审核 / 用户管理
   ▼
Vue 3 前端
   ├─ 游客端：列表、详情、搜索、登录、评论
   └─ 管理端：重索引、评论审核、用户管理、索引排查
```

**核心原则**：**源文件是唯一真相**，数据库只是索引产物，可随时全量重建。

---

## 三、技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 后端框架 | Spring Boot 3.x | Java 17+ |
| 认证 | Spring Security + JWT | 无状态 |
| 数据库 | SQLite（推荐）或 MySQL | 100 篇 SQLite 足够，零部署负担 |
| Markdown 渲染 | flexmark-java | 扩展性强，支持自定义 WikiLink/Callout |
| frontmatter | snakeyaml | 手动切 `---` 块解析 |
| 搜索 | SQLite FTS5 | 千篇内毫秒级 |
| 限流 | Bucket4j / 自定义计数 | 评论防刷 |
| 前端 | Vue 3 + Vite + Pinia + Vue Router | |
| 前端增强渲染 | mermaid.js、KaTeX、highlight.js | 按需动态加载 |
| 部署 | Nginx 反代 + HTTPS | |

---

## 四、数据模型

```sql
-- 笔记索引
note(
  id, path UNIQUE, slug UNIQUE, title, category,
  content_raw TEXT, content_html TEXT,
  content_hash, publish, deleted_at NULL,
  created_at, updated_at, indexed_at
)

tag(id, name UNIQUE)
note_tag(note_id, tag_id)
link(id, source_note_id, target_title, target_note_id NULL)

-- 用户
app_user(id, username UNIQUE, password_hash, role, created_at)
-- role: ADMIN / GUEST

-- 评论（两级回复）
comment(
  id, note_id, user_id, parent_id NULL,
  content, status,   -- PENDING / APPROVED / REJECTED / DELETED
  created_at
)

-- 全文搜索（SQLite）
note_fts(title, content)  -- FTS5 虚拟表
```

**关键约定**：
- `publish` 缺失 = 不发布（fail-safe，防止私密笔记泄露）
- 笔记软删（`deleted_at`），评论保留，避免误删丢评论
- `slug` 优先取 frontmatter，缺省用文件名/path hash，避免改名断链

---

## 五、核心模块设计

### 5.1 同步机制

**rsync 命令**：
```bash
rsync -az --delete \
  --exclude='.obsidian' --exclude='.trash' \
  ~/vault/ user@server:/srv/vault/ \
  && ssh user@server 'curl -s -X POST localhost:8080/api/admin/reindex \
       -H "X-Hook-Token: $HOOK_TOKEN"'
```

**三重保障**：
1. rsync 后 hook 触发（主路径）
2. cron 每 30 分钟兜底（防 hook 漏掉）
3. 管理后台手动重索引按钮

**原子性**：先同步到临时目录，完成后原子替换，避免后端读到半同步状态。

### 5.2 索引器（VaultIndexer）

**全量扫描**（千篇内毫秒级，无需增量）：

```
1. Files.walk(vaultRoot) → 过滤 .md、排除 .obsidian/.trash
2. 解析 frontmatter → 校验 publish:true（否则跳过）
3. 渲染 Markdown → content_html
4. upsert note + 更新 tag 关联 + 解析 link 表
5. cleanupDeleted()：源文件消失 → 软删 note
6. 重建 note_fts 索引
```

**幂等性**：可重复执行，结果一致。`content_hash` 可选用于跳过未变更笔记的重渲染。

### 5.3 Obsidian 方言处理策略（重点）

Obsidian 虽为 `.md` 后缀，但是方言，采用 **分层转换** 策略：

#### 第一层：必须转换（可标准化）

| Obsidian 语法 | 转换目标 | 实现方式 |
|---|---|---|
| `[[标题]]` / `[[标题\|别名]]` | `<a href="/note/{slug}">` | flexmark 自定义 LinkResolver + 建 link 表 |
| `![[img.png]]` / `![[img.png\|300]]` | `<img src="..." width="300">` | 路径重写 + 尺寸参数解析 |
| `#标签` | 标签索引 + 可点击链接 | flexmark 自定义扩展 |
| `==高亮==` | `<mark>` | flexmark 扩展 |
| `%%注释%%` | 直接移除 | 预处理剥离 |
| `> [!note] 标题` | `<div class="callout callout-note">` | 自定义 BlockParser/NodeRenderer |
| `> [!note]-` 折叠 | `<details>/<summary>` | Callout 扩展内处理 |

#### 第二层：复杂结构降级（前端增强）

| 语法 | 策略 |
|---|---|
| ` ```mermaid ` | 后端保留代码块，前端 mermaid.js 渲染 |
| `$...$` / `$$...$$` | 后端保留原文，前端 KaTeX 渲染 |
| ` ```java ` 代码块 | flexmark 原生支持 + highlight.js |

#### 第三层：插件运行时语法（剥离）

| 语法 | 策略 |
|---|---|
| ` ```dataview ` / ` ```dataviewjs ` | **索引时剥离**，避免暴露原始查询；如需列表功能，Vue 层单独实现简化版（最近更新/同标签）组件 |
| `^block-id` 块引用 | 去除（纯 Web 阅读无意义） |
| `![[整篇笔记]]` Transclusion | 一期**降级为普通链接**，避免递归与循环引用 |

**核心原则**：Markdown **只在后端渲染一次**，前端 `v-html` 直接展示，前后端不做两套解析（双链一致性会崩）。

### 5.4 认证授权

```
/api/auth/**          permitAll（注册、登录）
/api/notes/**         permitAll（公开阅读）
/api/comments (POST)  authenticated
/api/admin/**         hasRole('ADMIN')
```

**安全要点**：
- 注册接口**固定写死 GUEST**，绝不接受外部 `role` 参数（防越权）
- 管理员账号通过初始化 SQL / 种子脚本创建
- 密码 `BCryptPasswordEncoder`
- JWT 无状态，`JwtAuthFilter` 解析 `Authorization: Bearer`
- 敏感接口按 userId/IP 限流

### 5.5 评论系统

**状态机**：
```
PENDING（默认）──审核通过──▶ APPROVED（公开展示）
       │
       ├──管理员拒绝──▶ REJECTED
       └──删除──▶ DELETED（软删）
```

**规则**：
- 默认 `PENDING`，管理员手动/一键审核（100 篇小站成本低，防垃圾最省心）
- 公开接口只返回 `APPROVED`；`PENDING` 仅评论者本人和管理员可见
- 两级回复（`parent_id`），不做无限嵌套
- 限流：每分钟 3 条、每天 50 条
- 长度 1~2000 字，服务端 trim + 转义
- **评论渲染绝不 `v-html`**（防 XSS），纯文本或严格白名单

---

## 六、前端结构

```
pages/
├─ Home.vue            笔记列表（分类/标签筛选）
├─ NoteDetail.vue      正文（v-html）+ 反向链接 + 评论区
├─ Search.vue          搜索结果
├─ Login.vue / Register.vue
└─ admin/
    ├─ Dashboard.vue   统计 + 重索引按钮
    ├─ Comments.vue    审核队列（通过/拒绝/删除）
    ├─ Users.vue       用户管理（禁用/改角色）
    └─ Notes.vue       已索引笔记列表（排查未发布项）
```

**要点**：
- 路由守卫：`/admin` 需 `role === 'ADMIN'`
- 双链 `[[xx]]` 后端已渲染为链接，详情页额外展示"反向链接"（查 link 表）
- 代码高亮、Mermaid、KaTeX 按需动态加载，不阻塞首屏

---

## 七、部署与运维

| 项 | 方案 |
|---|---|
| 反向代理 | Nginx + HTTPS（Let's Encrypt） |
| SSH 安全 | key 限制到指定目录 + rrsync，不用 root |
| 备份 | `/srv/vault` + DB **每天定时备份**（笔记丢失是灾难级） |
| 版本控制 | 本地 vault 用 git 管，服务器可 `git pull` 替代 rsync（文本）；附件用 rsync |
| 日志 | Spring Boot 日志 + 索引结果记录（成功/失败/跳过数） |

---

## 八、实施计划

| 阶段 | 内容 | 产出 |
|---|---|---|
| **第 1 周** | Vault 同步 + 索引器跑通（frontmatter、双链、图片、写 DB） | `/api/notes` 可用，Postman 验证 |
| **第 2 周** | 认证（注册/登录/JWT）+ 角色门禁 + 重索引 hook | 管理后台可登录、可触发重索引 |
| **第 3 周** | 评论全流程（发表 → PENDING → 审核 → 展示） | 评论闭环可用 |
| **第 4 周** | 前端页面 + 搜索 + 反向链接 + 部署上线 | 可访问的正式站点 |

---

## 九、风险与注意事项

1. **注册越权**：`role` 字段永不接受外部输入，固定 GUEST。
2. **发布开关 fail-safe**：`publish: true` 才发布，缺失即不发布。
3. **`--delete` 与软删**：源文件删除 → 笔记软删，评论保留，防误删丢评论。
4. **XSS**：笔记 HTML 可信可用 `v-html`；**评论必须纯文本/白名单**。
5. **同步原子性**：临时目录 → 原子替换 → 再触发索引。
6. **备份**：vault + DB 每日备份，缺一不可。
7. **不过度设计**：100 篇规模，全量重索引 + 简单 SQL 足够；千篇以上再考虑增量、缓存、分库。
8. **Obsidian 方言的期望管理**：Dataview/Transclusion 一期降级，不要陷入插件运行时兼容泥潭。

---

## 十、方案总结

**架构核心**：rsync 同步 → hook 触发 → Spring Boot 全量索引 → DB 存储 → Vue 展示。源文件为唯一真相，DB 可重建。

**两块主要工作量**：
1. **索引器 + Obsidian 方言转换**（flexmark 定制扩展）
2. **评论审核流程**（状态机 + 权限 + 防刷）

**标准套路**：认证授权、JWT、前端路由守卫。

**规模适配**：100 篇用全量索引 + SQLite，把精力花在内容转换质量和体验上，而非架构扩展。
