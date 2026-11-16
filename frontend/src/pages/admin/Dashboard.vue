<script setup>
/**
 * 管理控制台首页。
 * 路由：/admin（admin-dashboard），仅管理员可访问（由全局路由守卫校验）。
 * 数据：GET /admin/stats 获取站点统计与最近一次索引结果。
 * 操作：POST /admin/reindex 触发全量重建索引，完成后重新拉取统计。
 */
import { computed, onMounted, ref } from 'vue'
import { api } from '../../api/client'
import { formatDateTime } from '../../utils/format'

const stats = ref(null)
const loading = ref(true)
const error = ref('')
// 索引请求进行中标记，用于禁用按钮并防止重复触发。
const indexing = ref(false)
// 本次重索引的返回结果，单独展示，区别于“最近一次索引”历史数据。
const indexResult = ref(null)

// 统计卡片由接口数据派生；数据未返回时给出空数组，避免模板访问 null 属性。
// 各 hint 说明指标口径，帮助管理员理解数字来源。
const cards = computed(() => {
  if (!stats.value) {
    return []
  }
  return [
    { label: '公开笔记', value: stats.value.publishedNotes, hint: `共索引 ${stats.value.totalNotes} 篇` },
    { label: '待审核评论', value: stats.value.pendingComments, hint: `共 ${stats.value.totalComments} 条评论` },
    { label: '草稿', value: stats.value.draftNotes, hint: '未设置 publish: true' },
    { label: '用户', value: stats.value.totalUsers, hint: '含管理员与游客' }
  ]
})

// 拉取控制台统计数据；失败时保留错误信息供页面顶部提示。
async function load() {
  loading.value = true
  error.value = ''
  try {
    stats.value = await api.get('/admin/stats')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

// 触发重新索引；索引可能耗时较长，因此先置位禁用按钮，结束后再恢复。
async function reindex() {
  if (indexing.value) {
    return
  }
  indexing.value = true
  error.value = ''
  indexResult.value = null
  try {
    indexResult.value = await api.post('/admin/reindex')
    // 重索引会改变笔记与统计口径，完成后刷新概览数据。
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    indexing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="admin-page">
    <!-- 页头：标题与全局“重新索引”入口，索引期间按钮禁用 -->
    <header class="admin-page-head">
      <div>
        <p class="eyebrow">Overview</p>
        <h1>控制台</h1>
        <p class="muted">索引状态、评论审核与站点数据一览。</p>
      </div>
      <button class="btn btn-primary" type="button" :disabled="indexing" @click="reindex">
        {{ indexing ? '索引中…' : '重新索引' }}
      </button>
    </header>

    <p v-if="error" class="alert alert-error">{{ error }}</p>
    <p v-if="loading" class="muted">加载中…</p>

    <!-- 统计数据就绪后才渲染主体，避免空数据导致的模板报错 -->
    <template v-if="stats">
      <!-- 关键指标卡片 -->
      <div class="stat-grid">
        <div v-for="card in cards" :key="card.label" class="stat-card stat-card-lg">
          <span class="muted small">{{ card.label }}</span>
          <strong>{{ card.value }}</strong>
          <span class="muted small">{{ card.hint }}</span>
        </div>
      </div>

      <!-- 最近一次索引的扫描结果，无记录时给出空态文案 -->
      <div class="panel">
        <div class="panel-head">
          <h2>最近一次索引</h2>
          <span class="muted small">{{ formatDateTime(stats.lastIndexedAt) || '尚未索引' }}</span>
        </div>
        <div v-if="stats.lastIndex" class="index-stats">
          <span>扫描 <strong>{{ stats.lastIndex.scanned }}</strong></span>
          <span>发布 <strong>{{ stats.lastIndex.published }}</strong></span>
          <span>未发布 <strong>{{ stats.lastIndex.unpublished }}</strong></span>
          <span>软删 <strong>{{ stats.lastIndex.deleted }}</strong></span>
          <span>失败 <strong>{{ stats.lastIndex.failed }}</strong></span>
          <span>耗时 <strong>{{ stats.lastIndex.durationMs }}ms</strong></span>
        </div>
        <p v-else class="muted">还没有索引记录。</p>
      </div>

      <!-- 仅本次手动触发索引后展示，便于即时核对结果 -->
      <div v-if="indexResult" class="panel">
        <div class="panel-head">
          <h2>本次重索引结果</h2>
        </div>
        <p class="alert alert-success">
          扫描 {{ indexResult.scanned }} 篇，发布 {{ indexResult.published }} 篇，
          软删 {{ indexResult.deleted }} 篇，失败 {{ indexResult.failed }} 篇，
          耗时 {{ indexResult.durationMs }}ms。
        </p>
      </div>

      <!-- 常用管理入口：评论审核、索引排查、用户管理 -->
      <div class="admin-shortcuts">
        <RouterLink class="shortcut" :to="{ name: 'admin-comments' }">
          <strong>处理评论</strong>
          <span>{{ stats.pendingComments }} 条待审核</span>
        </RouterLink>
        <RouterLink class="shortcut" :to="{ name: 'admin-notes' }">
          <strong>索引排查</strong>
          <span>查看未发布与软删笔记</span>
        </RouterLink>
        <RouterLink class="shortcut" :to="{ name: 'admin-users' }">
          <strong>用户管理</strong>
          <span>调整角色或禁用账号</span>
        </RouterLink>
      </div>
    </template>
  </div>
</template>