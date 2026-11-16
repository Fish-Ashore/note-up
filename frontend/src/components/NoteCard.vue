<script setup>
/**
 * 笔记卡片：列表页的最小展示单元，整卡跳转详情，底部最多展示 4 个标签。
 * 依赖 format.js 的日期格式化与标签链接构造；props.note 结构由后端列表接口决定。
 */
import { formatDate, tagUrl } from '../utils/format'

defineProps({
  note: {
    type: Object,
    required: true
  }
})
</script>

<template>
  <!-- 卡片主体：元信息、标题与摘要 -->
  <article class="note-card">
    <RouterLink class="note-card-link" :to="{ name: 'note', params: { slug: note.slug } }">
      <div class="note-card-head">
        <span v-if="note.category" class="badge">{{ note.category }}</span>
        <span v-else class="badge badge-muted">未分类</span>
        <time class="muted small">{{ formatDate(note.updatedAt) }}</time>
      </div>
      <h3>{{ note.title }}</h3>
      <p class="note-card-excerpt">{{ note.excerpt || '暂无摘要' }}</p>
    </RouterLink>
    <!-- 标签行：超出 4 个截断，避免破坏卡片高度一致性 -->
    <div v-if="note.tags?.length" class="tag-row">
      <RouterLink
        v-for="tag in note.tags.slice(0, 4)"
        :key="tag"
        class="tag"
        :to="tagUrl(tag)"
      >
        #{{ tag }}
      </RouterLink>
    </div>
  </article>
</template>