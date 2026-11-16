# Note-Up

把本地 Obsidian Vault 发布成可公开阅读、登录评论、管理员审核的网站。源文件保持 Obsidian 原生格式，数据库只是可随时重建的索引。

技术栈：

- 后端：Spring Boot 3.5 + Java 25 + Spring Security/JWT + SQLite + flexmark
- 前端：Vue 3 + Vite + Pinia + Vue Router
- 增强渲染：highlight.js、mermaid、KaTeX（按需加载）
- 搜索：SQLite FTS5
- 部署：Nginx + HTTPS

## 目录结构

```
note-up/
├─ backend/                 Spring Boot 后端
│  ├─ src/main/java/com/noteup/
│  │  ├─ service/           索引器、Obsidian 预处理、Markdown 渲染、业务服务
│  │  ├─ repo/              JdbcTemplate 数据访问
│  │  ├─ web/               REST 控制器
│  │  ├─ security/          JWT
│  │  └─ config/            配置、安全、种子管理员、启动索引
│  ├─ src/main/resources/schema.sql
│  └─ run.sh                自动选择 JDK 25 后启动
├─ frontend/                Vue 3 前端
├─ sample-vault/            示例 Obsidian Vault
├─ deploy/                  Nginx / systemd / 同步 / 备份示例
├─ dev.sh                   同时启动前后端
└─ TASK.md                  需求与设计文档
```

## 快速开始

要求：JDK 25、Maven 3.9+、Node.js 20+。

```bash
# 方式一：一键启动（首次会自动 npm install）
./dev.sh

# 方式二：分别启动
cd backend && ./run.sh
cd frontend && npm install && npm run dev
```

- 前端：<http://localhost:5173>
- 后端：<http://localhost:8080>
- 默认管理员：`admin` / `admin123`（请务必通过环境变量修改）
- 演示用户：`demo_reader` / `demo_writer` / `demo_ops`，密码均为 `demo123`
- 演示评论：`run.sh` 默认开启 `SEED_DEMO_DATA`，仅当评论表为空时写入
- 默认 Vault：`../sample-vault`（相对 `backend/` 工作目录）
- 默认数据库：`backend/data/notes.db`

如果直接运行 `mvn`，请确认 `JAVA_HOME` 指向 JDK 25；`backend/run.sh` 会根据 `javac` 自动推导。

## 配置

通过环境变量覆盖（见 `backend/.env.example` 与 `deploy/note-up.env.example`）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `VAULT_ROOT` | `../sample-vault` | Vault 根目录，建议绝对路径 |
| `DB_PATH` | `./data/notes.db` | SQLite 文件路径 |
| `JWT_SECRET` | 开发用默认值 | 生产必须替换，至少 32 字节随机串 |
| `JWT_EXPIRATION_HOURS` | `168` | token 有效期 |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | `admin` / `admin123` | 首次启动种子管理员 |
| `HOOK_TOKEN` | `dev-hook-token` | rsync hook 调用令牌 |
| `CORS_ORIGINS` | `http://localhost:5173,http://localhost:4173` | 允许的前端来源 |
| `INDEX_ON_STARTUP` | `true` | 启动时自动全量索引 |
| `SEED_DEMO_DATA` | `false`（`run.sh` 默认 `true`） | 评论库为空时写入演示用户与评论 |
| `SERVER_PORT` | `8080` | 后端端口 |

前端：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `VITE_API_BASE` | `/api` | 浏览器请求的 API 前缀 |
| `VITE_PROXY_TARGET` | `http://localhost:8080` | 仅开发代理目标 |

## 功能

### 公开阅读

- 笔记列表、分类/标签筛选、分页
- 笔记详情、标签、反向链接
- FTS5 全文搜索
- Vault 附件通过 `/api/assets/**` 提供
- 仅 `publish: true` 且未软删的笔记可见

### 认证与评论

- 注册固定为 `GUEST`，注册接口不接受 `role`
- 登录返回 JWT，无状态
- 新评论默认 `PENDING`，管理员通过后公开
- 演示种子包含 `APPROVED`、`PENDING`、`REJECTED`、`DELETED` 四种状态，可在管理后台查看
- 两级回复；评论者本人可看到自己的待审核评论
- 限流：每用户每分钟 3 条、每天 50 条；长度 1-2000 字
- 前端评论使用纯文本插值，不使用 `v-html`

### 管理后台

- 控制台：统计、最近索引结果、手动重索引
- 评论审核：通过 / 拒绝 / 删除
- 用户管理：修改角色、启用/禁用；保护最后一个启用的管理员
- 索引排查：查看全部已扫描笔记、未发布、软删状态

## API 概览

公开：

```
GET  /api/notes?page=&size=&category=&tag=&q=
GET  /api/notes/{slug}
GET  /api/notes/{slug}/comments
GET  /api/tags
GET  /api/categories
GET  /api/search?q=
GET  /api/assets/{path}
```

认证与评论：

```
POST /api/auth/register     { username, password }
POST /api/auth/login        { username, password }
GET  /api/auth/me
POST /api/comments          { slug, content, parentId? }
```

管理：

```
POST   /api/admin/reindex
GET    /api/admin/stats
GET    /api/admin/notes
GET    /api/admin/comments?status=&page=&size=
POST   /api/admin/comments/{id}/approve
POST   /api/admin/comments/{id}/reject
DELETE /api/admin/comments/{id}
GET    /api/admin/users
PATCH  /api/admin/users/{id}    { role?, enabled? }
```

Hook：

```
POST /api/hooks/reindex
Header: X-Hook-Token: <HOOK_TOKEN>
```

## Obsidian 方言支持

已转换：

- `[[标题]]` / `[[标题|别名]]` / `[[标题#标题]]` → 站内链接
- `![[图片.png]]` / `![[图片.png|300]]` → 图片与宽度
- `![[整篇笔记]]` → 降级为普通链接
- `#标签` / 多级标签 → 标签索引与可点击链接
- `==高亮==` → `<mark>`
- `%%注释%%` → 移除
- `> [!note] 标题` / `> [!tip]-` → Callout / `<details>`
- `^block-id` → 移除
- ` ```dataview ` / ` ```dataviewjs ` → 索引时剥离
- 行内/块级公式保留原文，前端 KaTeX 渲染
- ` ```mermaid ` 保留代码块，前端 Mermaid 渲染
- 标准 Markdown 代码块 + highlight.js 高亮

安全约定：

- 缺少 `publish` 或值不为 `true` → 不发布
- 源文件消失 → 软删笔记，评论保留
- 附件接口禁止 `.md`、隐藏目录与路径穿越

## 测试

```bash
cd backend
JAVA_HOME=/path/to/jdk-25 mvn test
```

测试覆盖：

- Obsidian 方言转换（双链、图片、Callout、Dataview、公式、块 ID）
- 全量索引：发布开关、标签、链接、反向链接、FTS 搜索
- 源文件删除后的软删行为

## 构建

```bash
cd backend
JAVA_HOME=/path/to/jdk-25 mvn -DskipTests package
# 产物：target/note-up-backend-1.0.0.jar

cd frontend
npm run build
# 产物：dist/
```

## 部署

参考 `deploy/`：

1. `deploy/nginx.conf.example`：静态前端 + `/api` 反代 + SPA fallback
2. `deploy/note-up.service`：systemd 服务
3. `deploy/note-up.env.example`：生产环境变量
4. `deploy/sync-vault.sh`：本地 rsync → 暂存目录 → 原子替换 → 触发 hook
5. `deploy/backup.sh`：Vault 打包 + SQLite `.backup`，保留 14 天

建议 crontab：

```cron
# Hook 兜底：每 30 分钟重索引
*/30 * * * * curl -sf -X POST http://127.0.0.1:8080/api/hooks/reindex -H 'X-Hook-Token: <HOOK_TOKEN>' >/dev/null

# 每日备份
15 3 * * * /opt/note-up/deploy/backup.sh >>/var/log/note-up-backup.log 2>&1
```

## 安全清单

- 生产环境必须替换 `JWT_SECRET`、`ADMIN_PASSWORD`、`HOOK_TOKEN`
- 只通过 HTTPS 暴露站点
- 笔记 HTML 视为可信内容并 `v-html` 展示；评论始终按纯文本渲染
- 数据库与 Vault 同时纳入每日备份
- 注册角色固定 `GUEST`，管理员通过种子账号创建
- 附件服务拒绝 `.md`、`.obsidian` 等隐藏路径和路径穿越