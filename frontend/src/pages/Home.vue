<script setup>
/**
 * 首页（路由 name: home，路径 /）
 *
 * 职责：展示公开笔记列表，并提供分类、标签筛选、分页与关键词搜索入口。
 * 路由参数（query）：page 页码（从 0 开始）、category 分类、tag 标签。
 * 列表状态全部由 URL 承载，刷新或分享链接后视图可以复现。
 * 数据来源：GET /notes（分页列表）、GET /categories 与 GET /tags（筛选项）。
 * 交互流程：筛选或翻页只修改查询参数，由 watch(route.query) 统一触发重新拉取。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, queryString } from '../api/client'
import NoteCard from '../components/NoteCard.vue'
import Pagination from '../components/Pagination.vue'

const route = useRoute()
const router = useRouter()

// 列表与筛选项的响应式状态；loading/error 用于驱动骨架屏和错误提示
const notes = ref([])
const total = ref(0)
const loading = ref(true)
const error = ref('')
const categories = ref([])
const tags = ref([])
const keyword = ref('')

// 从 URL 查询参数派生筛选条件，保证“地址即状态”，便于回退和分享
const page = computed(() => Number(route.query.page || 0))
const size = 9
const category = computed(() => String(route.query.category || ''))
const tag = computed(() => String(route.query.tag || ''))
const hasFilter = computed(() => Boolean(category.value || tag.value))

// 按当前页码与筛选条件拉取列表；失败时保留错误信息，交给模板展示
async function loadNotes() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.get(`/notes${queryString({
      page: page.value,
      size,
      category: category.value,
      tag: tag.value
    })}`)
    notes.value = data.items
    total.value = data.total
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

// 分类与标签可并行加载；标签只取前 16 个，避免筛选区过长
async function loadFilters() {
  try {
    const [categoryList, tagList] = await Promise.all([
      api.get('/categories'),
      api.get('/tags')
    ])
    categories.value = categoryList
    tags.value = tagList.slice(0, 16)
  } catch {
    // filters are optional
  }
}

// 统一更新查询参数：空值会被删除，防止 URL 里堆积 ?category=&page= 这类无效参数
function updateQuery(patch) {
  const query = { ...route.query, ...patch }
  Object.keys(query).forEach((key) => {
    if (query[key] === '' || query[key] === undefined || query[key] === null) {
      delete query[key]
    }
  })
  router.push({ name: 'home', query })
}

// 切换分类或标签时清空 page，避免停留在超出新结果范围的页码上
function setCategory(value) {
  updateQuery({ category: value || '', page: undefined })
}

function setTag(value) {
  updateQuery({ tag: value || '', page: undefined })
}

function setPage(value) {
  updateQuery({ page: value || undefined })
}

// 首页搜索只做跳转，实际检索由 Search 页面按 ?q= 执行，避免两处维护搜索逻辑
function submitSearch() {
  const value = keyword.value.trim()
  if (value) {
    router.push({ name: 'search', query: { q: value } })
  }
}

onMounted(() => {
  loadNotes()
  loadFilters()
})
// 深层监听查询参数，筛选、分页和浏览器前进后退都复用同一套加载逻辑
watch(() => route.query, loadNotes, { deep: true })
</script>

<template>
  <div class="page home-page">
    <!-- 首屏介绍区：搜索框提交后跳转到搜索页 -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow">Obsidian → Web</p>
        <h1>把本地笔记，变成可以慢慢读的网站</h1>
        <p class="hero-text">
          双链、标签、Callout、公式与图表都在这里保留下来。源文件仍然属于你的 Vault。
        </p>
        <form class="hero-search" @submit.prevent="submitSearch">
          <input v-model="keyword" type="search" placeholder="搜索标题或正文…" aria-label="搜索" />
          <button class="btn btn-primary" type="submit">搜索</button>
        </form>
      </div>
      <!-- 概览统计：笔记总数会随筛选条件变化 -->
      <div class="hero-stats">
        <div class="stat-card">
          <strong>{{ total }}</strong>
          <span>{{ hasFilter ? '筛选结果' : '篇公开笔记' }}</span>
        </div>
        <div class="stat-card">
          <strong>{{ categories.length }}</strong>
          <span>个分类</span>
        </div>
        <div class="stat-card">
          <strong>{{ tags.length }}</strong>
          <span>个标签</span>
        </div>
      </div>
    </section>

    <!-- 筛选区：分类与标签都通过修改 URL 查询参数生效 -->
    <section class="filters">
      <div class="filter-group">
        <span class="filter-label">分类</span>
        <button
          class="chip"
          :class="{ active: !category }"
          type="button"
          @click="setCategory('')"
        >
          全部
        </button>
        <button
          v-for="item in categories"
          :key="item"
          class="chip"
          :class="{ active: category === item }"
          type="button"
          @click="setCategory(item)"
        >
          {{ item }}
        </button>
      </div>
      <div v-if="tags.length" class="filter-group">
        <span class="filter-label">标签</span>
        <button
          class="chip"
          :class="{ active: !tag }"
          type="button"
          @click="setTag('')"
        >
          全部
        </button>
        <button
          v-for="item in tags"
          :key="item.name"
          class="chip"
          :class="{ active: tag === item.name }"
          type="button"
          @click="setTag(item.name)"
        >
          #{{ item.name }} <em>{{ item.count }}</em>
        </button>
      </div>
    </section>

    <!-- 列表区：错误提示独立展示，内容按 加载中 → 空结果 → 正常列表 分支渲染 -->
    <section class="content-section">
      <div class="section-heading">
        <h2>{{ hasFilter ? '筛选结果' : '最近更新' }}</h2>
        <span v-if="!loading" class="muted small">共 {{ total }} 篇</span>
      </div>

      <p v-if="error" class="alert alert-error">{{ error }}</p>
      <!-- 用 3 张骨架卡占位，避免加载期间列表区域高度跳动 -->
      <div v-if="loading" class="note-grid">
        <div v-for="n in 3" :key="n" class="note-card skeleton"></div>
      </div>
      <div v-else-if="notes.length === 0" class="empty-state">
        没有找到符合条件的笔记。
      </div>
      <div v-else class="note-grid">
        <NoteCard v-for="note in notes" :key="note.id" :note="note" />
      </div>

      <!-- 分页组件通过 update:page 事件回写 URL -->
      <Pagination
        :page="page"
        :size="size"
        :total="total"
        @update:page="setPage"
      />
    </section>
  </div>
</template>