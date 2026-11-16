<script setup>
/**
 * 根组件：提供全局页面骨架（导航栏 + 路由出口 + 页脚）。
 * 挂载时调用 auth.restore() 恢复登录态，使刷新页面后仍能识别当前用户；
 * restore 内部有 ready 标记做幂等，因此与路由守卫中的恢复逻辑重复调用也无副作用。
 */
import { onMounted } from 'vue'
import NavBar from './components/NavBar.vue'
import { useAuthStore } from './stores/auth'

const auth = useAuthStore()

// 首次挂载时尝试用本地 token 换取用户信息，失败则由 store 内部清理
onMounted(() => {
  auth.restore()
})
</script>

<template>
  <!-- 整站布局：页脚固定在内容区下方，短页面时也能撑满视口 -->
  <div class="app-shell">
    <NavBar />
    <main class="app-main">
      <RouterView />
    </main>
    <footer class="app-footer">
      <span>Note-Up · 源文件是唯一真相，数据库只是索引</span>
    </footer>
  </div>
</template>