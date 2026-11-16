<script setup>
/**
 * 笔记详情页（路由 name: note，路径 /notes/:slug）
 *
 * 职责：按 slug 加载单篇笔记，渲染 Markdown HTML、分类/标签、反向链接与评论区。
 * 路由参数：params.slug 笔记唯一标识；变化时重新加载，支持站内连续跳转。
 * 数据来源：GET /notes/:slug；评论由子组件 CommentSection 自行加载。
 * 交互流程：进入页面加载 → 成功则同步 document.title → 失败展示错误态。
 */
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api/client'
import MarkdownContent from '../components/MarkdownContent.vue'
import CommentSection from '../components/CommentSection.vue'
import { formatDate, tagUrl } from '../utils/format'

const route = useRoute()
const note = ref(null)
const loading = ref(true)
const error = ref('')

// 页面标题已在 header 单独渲染；若正文首个 h1 与标题重复则去掉，避免出现两个相同大标题
const contentHtml = computed(() => {
  const html = note.value?.html || ''
  const match = html.match(/^\s*<h1[^>]*>([\s\S]*?)<\/h1>\s*/i)
  if (match) {
    const text = match[1].replace(/<[^>]+>/g, '').trim()
    if (text === note.value.title.trim()) {
      return html.slice(match[0].length)
    }
  }
  return html
})

// 切换笔记前先清空旧数据，防止新内容到达前短暂显示上一篇的内容
async function load() {
  loading.value = true
  error.value = ''
  note.value = null
  try {
    // slug 可能含中文或特殊字符，必须先编码再拼进接口路径
    note.value = await api.get(`/notes/${encodeURIComponent(route.params.slug)}`)
    document.title = `${note.value.title} · Note-Up`
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

// slug 变化即重新加载（immediate 覆盖首次进入），站内上一篇/下一篇跳转也会复用此逻辑
watch(() => route.params.slug, load, { immediate: true })
</script>

<template>
  <div class="page note-page">
    <RouterLink class="back-link" to="/">← 返回笔记列表</RouterLink>

    <p v-if="loading" class="muted">加载中…</p>
    <div v-else-if="error" class="empty-state">
      <h2>无法打开这篇笔记</h2>
      <p>{{ error }}</p>
      <RouterLink class="btn btn-primary" to="/">回到首页</RouterLink>
    </div>

    <template v-else-if="note">
      <!-- 正文区：header 展示标题与元信息，MarkdownContent 负责渲染富文本 -->
      <article class="note-article">
        <header class="note-header">
          <div class="note-meta">
            <span v-if="note.category" class="badge">{{ note.category }}</span>
            <span class="muted small">更新于 {{ formatDate(note.updatedAt) }}</span>
          </div>
          <h1>{{ note.title }}</h1>
          <div v-if="note.tags?.length" class="tag-row">
            <RouterLink
              v-for="item in note.tags"
              :key="item"
              class="tag"
              :to="tagUrl(item)"
            >
              #{{ item }}
            </RouterLink>
          </div>
        </header>

        <MarkdownContent :html="contentHtml" />
      </article>

      <!-- 反向链接：其他笔记指向当前页面的引用，仅在存在时渲染 -->
      <aside v-if="note.backlinks?.length" class="backlinks">
        <h2>反向链接</h2>
        <p class="muted small">这些笔记链接到了当前页面</p>
        <ul>
          <li v-for="item in note.backlinks" :key="item.slug">
            <RouterLink :to="{ name: 'note', params: { slug: item.slug } }">
              {{ item.title }}
            </RouterLink>
          </li>
        </ul>
      </aside>

      <!-- 评论区：传入 slug 由子组件自行处理登录态、加载与提交 -->
      <CommentSection :slug="note.slug" />
    </template>
  </div>
</template>