<script setup>
/**
 * 搜索页（路由 name: search，路径 /search）
 *
 * 职责：根据关键词调用全文搜索接口，展示标题、分类与命中片段。
 * 路由参数（query）：q 搜索关键词，是唯一的数据来源，直接访问带 q 的链接即可复现结果。
 * 数据来源：GET /search?q=...&limit=30。
 * 交互流程：表单提交只改写 URL → watch(route.query.q) 触发检索 → 按加载/错误/空结果分支渲染。
 */
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, queryString } from '../api/client'

const route = useRoute()
const router = useRouter()
const keyword = ref(String(route.query.q || ''))
const results = ref([])
const loading = ref(false)
const error = ref('')

// 关键词以 route.query.q 为准而非输入框；清空关键词时直接重置结果，无需请求
async function search() {
  const q = String(route.query.q || '').trim()
  if (!q) {
    results.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    results.value = await api.get(`/search${queryString({ q, limit: 30 })}`)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

// 推进同路由但参数不同的地址，交由下面的 watch 统一发起请求
function submit() {
  const value = keyword.value.trim()
  if (value) {
    router.push({ name: 'search', query: { q: value } })
  }
}

// 同步输入框并立即检索；immediate 保证首次进入带 q 的链接时也能出结果
watch(() => route.query.q, (value) => {
  keyword.value = String(value || '')
  search()
}, { immediate: true })
</script>

<template>
  <div class="page search-page">
    <!-- 搜索输入区：提交后只改变 URL 中的 q -->
    <section class="page-intro">
      <p class="eyebrow">全文搜索</p>
      <h1>搜索笔记</h1>
      <form class="hero-search" @submit.prevent="submit">
        <input v-model="keyword" type="search" placeholder="输入关键词，例如：索引器、Callout…" />
        <button class="btn btn-primary" type="submit">搜索</button>
      </form>
      <p v-if="route.query.q" class="muted small">
        “{{ route.query.q }}” 的搜索结果
      </p>
    </section>

    <p v-if="error" class="alert alert-error">{{ error }}</p>
    <p v-if="loading" class="muted">搜索中…</p>

    <!-- 结果列表：空结果提示仅在请求结束、有 q 且无数据时出现 -->
    <div v-if="!loading && route.query.q && results.length === 0" class="empty-state">
      没有找到匹配的笔记，换一个关键词试试。
    </div>

    <!-- 命中条数较多，列表不展示分页；snippet 由后端高亮并返回 HTML -->
    <ul v-if="results.length" class="search-results">
      <li v-for="hit in results" :key="hit.id" class="search-result">
        <RouterLink :to="{ name: 'note', params: { slug: hit.slug } }">
          <div class="search-result-head">
            <h3>{{ hit.title }}</h3>
            <span v-if="hit.category" class="badge">{{ hit.category }}</span>
          </div>
          <p class="search-snippet" v-html="hit.snippet"></p>
        </RouterLink>
      </li>
    </ul>
  </div>
</template>