<script setup>
/**
 * 全站顶部导航：品牌入口、主导航、快捷搜索与用户区。
 * 数据流：搜索框输入 → 路由跳转到 search 页并带上 q 查询；
 *        用户区根据 auth store 的登录/管理员状态切换展示。
 * 依赖：vue-router 负责跳转，auth store 提供登录态与登出动作。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const keyword = ref('')

function submitSearch() {
  const value = keyword.value.trim()
  // 空白关键词不跳转，避免产生无意义的空搜索页
  if (!value) {
    return
  }
  router.push({ name: 'search', query: { q: value } })
  // 跳转后清空输入，便于再次搜索
  keyword.value = ''
}

function logout() {
  auth.logout()
  // 登出后回首页，避免停留在需要身份的页面
  router.push('/')
}
</script>

<template>
  <!-- 顶部导航：左侧品牌与链接，中部搜索，右侧用户操作 -->
  <header class="navbar">
    <div class="navbar-inner container">
      <RouterLink class="brand" to="/">
        <span class="brand-mark">N</span>
        <span class="brand-text">Note-Up<small>笔记发布站</small></span>
      </RouterLink>

      <!-- 主导航；管理入口仅管理员可见 -->
      <nav class="nav-links">
        <RouterLink to="/">首页</RouterLink>
        <RouterLink :to="{ name: 'search' }">搜索</RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin">管理</RouterLink>
      </nav>

      <!-- 快捷搜索，回车或点按钮均触发 -->
      <form class="nav-search" @submit.prevent="submitSearch">
        <input v-model="keyword" type="search" placeholder="搜索笔记…" aria-label="搜索笔记" />
        <button type="submit" class="btn btn-primary btn-sm">搜索</button>
      </form>

      <!-- 用户区：已登录显示用户名与退出，未登录显示登录/注册 -->
      <div class="nav-user">
        <template v-if="auth.isLoggedIn">
          <span class="user-chip">
            {{ auth.username }}
            <em v-if="auth.isAdmin">管理员</em>
          </span>
          <button class="btn btn-ghost btn-sm" type="button" @click="logout">退出</button>
        </template>
        <template v-else>
          <RouterLink class="btn btn-ghost btn-sm" to="/login">登录</RouterLink>
          <RouterLink class="btn btn-primary btn-sm" to="/register">注册</RouterLink>
        </template>
      </div>
    </div>
  </header>
</template>