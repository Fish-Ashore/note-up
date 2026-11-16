<script setup>
/**
 * 分页组件：受控模式，page 由父组件传入，翻页通过 update:page 事件回传。
 * page 从 0 开始（与后端分页参数一致），展示时再 +1 转为人类可读页码。
 * 依赖 props 的 total/size 计算总页数；仅当总数超过一页时才渲染。
 */
import { computed } from 'vue'

const props = defineProps({
  page: { type: Number, default: 0 },
  size: { type: Number, default: 12 },
  total: { type: Number, default: 0 }
})

const emit = defineEmits(['update:page'])

// 至少保留 1 页，避免 total=0 时出现“第 1 / 0 页”
const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.size)))
const hasPrev = computed(() => props.page > 0)
const hasNext = computed(() => props.page + 1 < totalPages.value)

function go(page) {
  // 越界页码直接忽略，作为按钮 disabled 之外的第二道防线
  if (page >= 0 && page < totalPages.value) {
    emit('update:page', page)
  }
}
</script>

<template>
  <!-- 仅一页时无需分页控件 -->
  <div v-if="total > size" class="pagination">
    <button class="btn btn-ghost btn-sm" :disabled="!hasPrev" @click="go(page - 1)">上一页</button>
    <span class="small muted">第 {{ page + 1 }} / {{ totalPages }} 页 · 共 {{ total }} 篇</span>
    <button class="btn btn-ghost btn-sm" :disabled="!hasNext" @click="go(page + 1)">下一页</button>
  </div>
</template>