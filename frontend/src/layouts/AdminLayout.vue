<script setup>
/**
 * 管理后台整体布局。
 * 路由：/admin 及其子路由（admin-dashboard、admin-comments、admin-users、admin-notes）。
 * 权限：父路由 meta.requiresAdmin 为 true，由全局路由守卫统一校验；
 *       未登录跳转登录页，已登录但非管理员则返回首页。
 * 职责：提供侧边导航、当前账号信息与子页面出口，页面数据由各子页面自行加载。
 */
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'

// 当前路由用于高亮对应导航项；auth 提供当前登录用户名。
const route = useRoute()
const auth = useAuthStore()

// 侧边导航配置：name 与路由名一一对应，供循环渲染与高亮判断复用。
const links = [
  { name: 'admin-dashboard', label: '控制台', icon: '◉' },
  { name: 'admin-comments', label: '评论审核', icon: '✎' },
  { name: 'admin-users', label: '用户管理', icon: '◎' },
  { name: 'admin-notes', label: '索引排查', icon: '▤' }
]
</script>

<template>
  <div class="admin-shell">
    <!-- 左侧导航区：品牌、菜单与账号信息 -->
    <aside class="admin-sidebar">
      <div class="admin-brand">
        <span class="eyebrow">管理后台</span>
        <strong>Note-Up</strong>
      </div>
      <!-- 导航项由 links 配置渲染，route.name 匹配时高亮 -->
      <nav class="admin-nav">
        <RouterLink
          v-for="link in links"
          :key="link.name"
          :to="{ name: link.name }"
          class="admin-nav-link"
          :class="{ active: route.name === link.name }"
        >
          <span class="admin-nav-icon">{{ link.icon }}</span>
          {{ link.label }}
        </RouterLink>
      </nav>
      <!-- 底部展示当前管理员并提供返回前台站点的入口 -->
      <div class="admin-sidebar-footer">
        <span class="muted small">当前账号</span>
        <strong>{{ auth.username }}</strong>
        <RouterLink class="link-button" to="/">← 返回站点</RouterLink>
      </div>
    </aside>
    <!-- 右侧内容区：渲染当前管理子路由页面 -->
    <section class="admin-content">
      <RouterView />
    </section>
  </div>
</template>