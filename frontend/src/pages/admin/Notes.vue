<script setup>
/**
 * 索引排查页。
 * 路由：/admin/notes（admin-notes），仅管理员可访问（由全局路由守卫校验）。
 * 数据：一次性拉取 GET /admin/notes 返回的全部已扫描笔记。
 * 流程：状态筛选与关键字搜索均在前端本地完成，不触发额外请求；
 *       用于定位未发布、已软删或索引异常的源文件。
 */
import { computed, onMounted, ref } from 'vue'
import { api } from '../../api/client'
import { formatDateTime } from '../../utils/format'

const notes = ref([])
const loading = ref(true)
const error = ref('')
// 状态筛选：ALL / PUBLISHED / DRAFT / DELETED。
const filter = ref('ALL')
const keyword = ref('')

// 前端过滤：先按状态口径筛选，再按标题、路径、slug 做不区分大小写的模糊匹配。
const filtered = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return notes.value.filter((note) => {
    if (filter.value === 'PUBLISHED' && !note.publish) {
      return false
    }
    // 未发布口径需排除已软删，避免把软删笔记误判为草稿。
    if (filter.value === 'DRAFT' && (note.publish || note.deletedAt)) {
      return false
    }
    if (filter.value === 'DELETED' && !note.deletedAt) {
      return false
    }
    if (text && !`${note.title} ${note.path} ${note.slug}`.toLowerCase().includes(text)) {
      return false
    }
    return true
  })
})

// 各标签的计数口径与 filtered 保持一致：软删笔记不计入已发布 / 未发布。
const counts = computed(() => ({
  all: notes.value.length,
  published: notes.value.filter((note) => note.publish && !note.deletedAt).length,
  draft: notes.value.filter((note) => !note.publish && !note.deletedAt).length,
  deleted: notes.value.filter((note) => note.deletedAt).length
}))

// 拉取全部笔记供本地筛选用；接口失败时展示错误提示。
async function load() {
  loading.value = true
  error.value = ''
  try {
    notes.value = await api.get('/admin/notes')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="admin-page">
    <!-- 页头：说明本页用途 -->
    <header class="admin-page-head">
      <div>
        <p class="eyebrow">Index</p>
        <h1>索引排查</h1>
        <p class="muted">查看所有被扫描到的源文件，定位未发布、失败或已软删的笔记。</p>
      </div>
    </header>

    <!-- 工具栏：状态标签（带计数）与本地关键字搜索 -->
    <div class="toolbar">
      <div class="tabs">
        <button class="tab" :class="{ active: filter === 'ALL' }" @click="filter = 'ALL'">
          全部 {{ counts.all }}
        </button>
        <button class="tab" :class="{ active: filter === 'PUBLISHED' }" @click="filter = 'PUBLISHED'">
          已发布 {{ counts.published }}
        </button>
        <button class="tab" :class="{ active: filter === 'DRAFT' }" @click="filter = 'DRAFT'">
          未发布 {{ counts.draft }}
        </button>
        <button class="tab" :class="{ active: filter === 'DELETED' }" @click="filter = 'DELETED'">
          软删 {{ counts.deleted }}
        </button>
      </div>
      <input v-model="keyword" class="toolbar-search" type="search" placeholder="按标题、路径、slug 过滤…" />
    </div>

    <!-- 错误、加载与空结果提示 -->
    <p v-if="error" class="alert alert-error">{{ error }}</p>
    <p v-if="loading" class="muted">加载中…</p>
    <div v-else-if="filtered.length === 0" class="empty-state">没有符合条件的笔记。</div>

    <!-- 笔记清单：状态列按“软删 > 已发布 > 未发布”的优先级展示 -->
    <div v-else class="table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th>标题</th>
            <th>路径</th>
            <th>分类</th>
            <th>状态</th>
            <th>索引时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="note in filtered" :key="note.id">
            <td>
              <strong>{{ note.title }}</strong>
              <div class="muted small">/{{ note.slug }}</div>
            </td>
            <td class="mono small">{{ note.path }}</td>
            <td>{{ note.category || '—' }}</td>
            <td>
              <span v-if="note.deletedAt" class="status status-deleted">已软删</span>
              <span v-else-if="note.publish" class="status status-approved">已发布</span>
              <span v-else class="status status-pending">未发布</span>
            </td>
            <td class="muted small">{{ formatDateTime(note.indexedAt) || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>