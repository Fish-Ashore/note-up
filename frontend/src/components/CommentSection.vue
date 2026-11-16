<script setup>
/**
 * 笔记评论区：负责加载公开评论、提交新评论/回复，并展示审核提示。
 * 数据流：slug 变化或登录用户切换 → load 拉取列表 → 用户提交 → 后端入库（待审核）
 *        → 本地清空输入并重新拉取，因此列表始终以后端审核结果为准。
 * 依赖：api/client 提供接口，auth store 判断登录态，format.js 统一时间格式。
 */
import { nextTick, onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import { useAuthStore } from '../stores/auth'
import { formatDateTime } from '../utils/format'

const props = defineProps({
  slug: { type: String, required: true }
})

const auth = useAuthStore()
const comments = ref([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const content = ref('')
const submitting = ref(false)
// null 表示顶层评论；有值时记录被回复评论的 id 与用户名
const replyTo = ref(null)
// 模板 ref，用于开始回复时自动聚焦输入框
const textarea = ref(null)

async function load() {
  loading.value = true
  error.value = ''
  try {
    // slug 可能含特殊字符，拼接路径前需编码
    comments.value = await api.get(`/notes/${encodeURIComponent(props.slug)}/comments`)
  } catch (e) {
    error.value = e.message
  } finally {
    // 无论成功失败都要结束加载态，避免骨架/提示卡死
    loading.value = false
  }
}

function startReply(comment) {
  replyTo.value = { id: comment.id, username: comment.username }
  // 等 DOM 更新出回复提示后再聚焦，否则 ref 可能尚未就绪
  nextTick(() => textarea.value?.focus())
}

function cancelReply() {
  replyTo.value = null
}

async function submit() {
  // 双保险：空内容或重复提交（连点）直接忽略
  if (!content.value.trim() || submitting.value) {
    return
  }
  submitting.value = true
  error.value = ''
  notice.value = ''
  try {
    await api.post('/comments', {
      slug: props.slug,
      content: content.value,
      // 顶层评论显式传 null，避免后端把 undefined 当缺失字段
      parentId: replyTo.value?.id ?? null
    })
    content.value = ''
    replyTo.value = null
    notice.value = '评论已提交，审核通过后会公开显示。'
    // 重新拉取以获取服务端生成的 id、时间与审核状态
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    submitting.value = false
  }
}

onMounted(load)
// 切换笔记时重新加载对应评论
watch(() => props.slug, load)
// 登录/登出后可见范围会变化（管理员可见待审核项），需刷新列表
watch(() => auth.user?.id, load)
</script>

<template>
  <!-- 评论区容器：标题、输入区、状态提示与评论列表 -->
  <section class="comments-section">
    <div class="section-heading">
      <h2>评论</h2>
      <span class="muted small">{{ comments.length ? '仅展示已审核评论' : '' }}</span>
    </div>

    <!-- 未登录只显示引导，不渲染表单 -->
    <div v-if="!auth.isLoggedIn" class="notice">
      <RouterLink :to="{ name: 'login', query: { redirect: `/notes/${slug}` } }">登录</RouterLink>
      后可以发表评论。
    </div>

    <!-- 发表评论 / 回复表单 -->
    <form v-else class="comment-form" @submit.prevent="submit">
      <div v-if="replyTo" class="reply-hint">
        正在回复 @{{ replyTo.username }}
        <button type="button" class="link-button" @click="cancelReply">取消</button>
      </div>
      <textarea
        ref="textarea"
        v-model="content"
        rows="4"
        maxlength="2000"
        placeholder="写下你的评论…（1-2000 字，提交后需管理员审核）"
      ></textarea>
      <div class="comment-form-actions">
        <span class="muted small">{{ content.length }} / 2000</span>
        <button class="btn btn-primary" type="submit" :disabled="submitting || !content.trim()">
          {{ submitting ? '提交中…' : '发表评论' }}
        </button>
      </div>
    </form>

    <!-- 反馈信息：成功提示、错误与加载态 -->
    <p v-if="notice" class="alert alert-success">{{ notice }}</p>
    <p v-if="error" class="alert alert-error">{{ error }}</p>
    <p v-if="loading" class="muted">评论加载中…</p>

    <div v-if="!loading && comments.length === 0" class="empty-state">
      还没有已公开的评论，来抢沙发吧。
    </div>

    <!-- 评论列表：顶层评论内嵌二级回复，仅支持一层嵌套 -->
    <ul class="comment-list">
      <li v-for="comment in comments" :key="comment.id" class="comment-item">
        <div class="comment-head">
          <strong>{{ comment.username }}</strong>
          <span class="muted small">{{ formatDateTime(comment.createdAt) }}</span>
          <span v-if="comment.status === 'PENDING'" class="status status-pending">待审核</span>
        </div>
        <p class="comment-content">{{ comment.content }}</p>
        <div class="comment-actions">
          <button
            v-if="auth.isLoggedIn"
            type="button"
            class="link-button"
            @click="startReply(comment)"
          >
            回复
          </button>
        </div>

        <!-- 二级回复 -->
        <ul v-if="comment.replies?.length" class="comment-replies">
          <li v-for="reply in comment.replies" :key="reply.id" class="comment-item reply-item">
            <div class="comment-head">
              <strong>{{ reply.username }}</strong>
              <span class="muted small">{{ formatDateTime(reply.createdAt) }}</span>
              <span v-if="reply.status === 'PENDING'" class="status status-pending">待审核</span>
            </div>
            <p class="comment-content">{{ reply.content }}</p>
          </li>
        </ul>
      </li>
    </ul>
  </section>
</template>