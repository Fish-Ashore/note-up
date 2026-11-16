<script setup>
/**
 * 评论审核页。
 * 路由：/admin/comments（admin-comments），仅管理员可访问（由全局路由守卫校验）。
 * 数据：GET /admin/comments 按状态分页拉取评论；GET /admin/notes 用于把 noteId 映射为可跳转的笔记标题。
 * 操作：通过 / 拒绝 / 删除评论，成功后重新加载当前页；
 *       状态流转为 PENDING → APPROVED 或 REJECTED，删除后为 DELETED。
 */
import { computed, onMounted, ref } from 'vue'
import { api, queryString } from '../../api/client'
import Pagination from '../../components/Pagination.vue'
import { formatDateTime } from '../../utils/format'

// 筛选项：待审核放在首位便于处理积压；value 为空表示不传 status，查询全部。
const statuses = [
  { value: 'PENDING', label: '待审核' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已拒绝' },
  { value: 'DELETED', label: '已删除' },
  { value: '', label: '全部' }
]

// 默认只看待审核；页码从 0 开始，与后端分页参数保持一致。
const activeStatus = ref('PENDING')
const page = ref(0)
const size = 20
const comments = ref([])
const total = ref(0)
const loading = ref(true)
const error = ref('')
// 正在执行操作的评论 ID，用于禁用按钮并阻止并发提交。
const busyId = ref(null)
// noteId → 笔记对象，用于展示评论所属笔记的标题与链接。
const noteMap = ref({})

// 状态值到中文文案的映射，供列表徽标展示。
const statusLabel = computed(() => {
  const map = { PENDING: '待审核', APPROVED: '已通过', REJECTED: '已拒绝', DELETED: '已删除' }
  return map
})

// 按当前筛选状态与页码拉取评论；queryString 会忽略空值，因此“全部”不会携带 status。
async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.get(`/admin/comments${queryString({
      status: activeStatus.value,
      page: page.value,
      size
    })}`)
    comments.value = data.items
    total.value = data.total
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

// 预加载笔记列表，把评论的 noteId 转成标题和 slug；失败时不影响审核主流程，故静默忽略。
async function loadNotes() {
  try {
    const notes = await api.get('/admin/notes')
    noteMap.value = Object.fromEntries(notes.map((note) => [note.id, note]))
  } catch {
    // optional
  }
}

// 切换筛选时重置到第一页，避免停留在超出新结果集的页码上。
function switchStatus(value) {
  activeStatus.value = value
  page.value = 0
  load()
}

function changePage(value) {
  page.value = value
  load()
}

// 执行审核动作：action 为 approve / reject / delete，接口路径除 delete 外共用同一前缀。
async function act(comment, action) {
  // 任一操作进行中时直接返回，防止重复点击产生并发请求。
  if (busyId.value) {
    return
  }
  // 删除不可恢复，先让用户确认。
  if (action === 'delete' && !window.confirm('确定删除这条评论吗？')) {
    return
  }
  busyId.value = comment.id
  error.value = ''
  try {
    if (action === 'delete') {
      await api.delete(`/admin/comments/${comment.id}`)
    } else {
      await api.post(`/admin/comments/${comment.id}/${action}`)
    }
    // 操作成功后重新拉取，确保列表与总数与服务端一致。
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busyId.value = null
  }
}

onMounted(() => {
  load()
  loadNotes()
})
</script>

<template>
  <div class="admin-page">
    <!-- 页头：说明审核规则 -->
    <header class="admin-page-head">
      <div>
        <p class="eyebrow">Moderation</p>
        <h1>评论审核</h1>
        <p class="muted">新评论默认待审核，通过后才会在公开页面展示。</p>
      </div>
    </header>

    <!-- 状态筛选：切换后重置页码并重新请求 -->
    <div class="tabs">
      <button
        v-for="item in statuses"
        :key="item.value"
        type="button"
        class="tab"
        :class="{ active: activeStatus === item.value }"
        @click="switchStatus(item.value)"
      >
        {{ item.label }}
      </button>
    </div>

    <!-- 错误、加载与空状态互斥展示 -->
    <p v-if="error" class="alert alert-error">{{ error }}</p>
    <p v-if="loading" class="muted">加载中…</p>
    <div v-else-if="comments.length === 0" class="empty-state">
      当前筛选下没有评论。
    </div>

    <!-- 评论列表：每条展示作者、时间、状态、所属笔记与操作按钮 -->
    <ul v-else class="admin-list">
      <li v-for="comment in comments" :key="comment.id" class="admin-list-item">
        <div class="admin-list-head">
          <div>
            <strong>{{ comment.username }}</strong>
            <span class="muted small"> · {{ formatDateTime(comment.createdAt) }}</span>
            <span class="status" :class="`status-${comment.status.toLowerCase()}`">
              {{ statusLabel[comment.status] }}
            </span>
          </div>
          <!-- 能匹配到笔记时跳转详情，否则退化为显示笔记 ID -->
          <RouterLink
            v-if="noteMap[comment.noteId]"
            class="link-button"
            :to="{ name: 'note', params: { slug: noteMap[comment.noteId].slug } }"
          >
            {{ noteMap[comment.noteId].title }}
          </RouterLink>
          <span v-else class="muted small">笔记 #{{ comment.noteId }}</span>
        </div>
        <p class="comment-content">{{ comment.content }}</p>
        <!-- 操作区：按当前状态隐藏无意义的按钮，例如已通过的评论不再显示“通过” -->
        <div class="admin-list-actions">
          <button
            v-if="comment.status !== 'APPROVED'"
            class="btn btn-primary btn-sm"
            type="button"
            :disabled="busyId === comment.id"
            @click="act(comment, 'approve')"
          >
            通过
          </button>
          <button
            v-if="comment.status !== 'REJECTED'"
            class="btn btn-ghost btn-sm"
            type="button"
            :disabled="busyId === comment.id"
            @click="act(comment, 'reject')"
          >
            拒绝
          </button>
          <button
            v-if="comment.status !== 'DELETED'"
            class="btn btn-danger btn-sm"
            type="button"
            :disabled="busyId === comment.id"
            @click="act(comment, 'delete')"
          >
            删除
          </button>
        </div>
      </li>
    </ul>

    <!-- 分页：页码变化后由父组件重新加载 -->
    <Pagination :page="page" :size="size" :total="total" @update:page="changePage" />
  </div>
</template>