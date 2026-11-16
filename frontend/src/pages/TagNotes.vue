<script setup>
/**
 * 标签聚合页（路由 name: tag，路径 /tags）
 *
 * 职责：列出某个标签下的全部公开笔记。
 * 路由参数（query）：name 标签名（由 tagUrl 生成，vue-router 会自动解码）。
 * 数据来源：GET /notes?tag=...&size=100。
 * 交互流程：标签名变化时重新加载；无标签时直接进入空状态，不发请求。
 */
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, queryString } from '../api/client'
import NoteCard from '../components/NoteCard.vue'

const route = useRoute()
const notes = ref([])
const loading = ref(true)
const error = ref('')

const tagName = computed(() => String(route.query.name || ''))

// 缺少标签名时没有可查询的条件，直接结束 loading 走空状态
async function load() {
  if (!tagName.value) {
    notes.value = []
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    // 标签页不做分页，一次取 100 条作为上限，避免超大标签拖慢首屏
    const data = await api.get(`/notes${queryString({ tag: tagName.value, size: 100 })}`)
    notes.value = data.items
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

// 标签由查询参数驱动，可在一页内切换不同标签；首次加载也由此完成
watch(tagName, load, { immediate: true })
</script>

<template>
  <div class="page">
    <!-- 标签标题与计数：计数使用当前已加载的列表长度 -->
    <section class="page-intro">
      <p class="eyebrow">标签</p>
      <h1>#{{ tagName }}</h1>
      <p class="muted">共 {{ notes.length }} 篇笔记</p>
    </section>

    <!-- 状态分支：错误提示优先，其次加载中，最后是空结果或卡片列表 -->
    <p v-if="error" class="alert alert-error">{{ error }}</p>
    <p v-if="loading" class="muted">加载中…</p>
    <div v-else-if="notes.length === 0" class="empty-state">
      这个标签下还没有公开笔记。
    </div>
    <div v-else class="note-grid">
      <NoteCard v-for="note in notes" :key="note.id" :note="note" />
    </div>
  </div>
</template>