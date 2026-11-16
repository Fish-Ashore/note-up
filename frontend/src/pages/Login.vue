<script setup>
/**
 * 登录页（路由 name: login，路径 /login）
 *
 * 职责：收集用户名与密码，调用认证 store 登录，成功后跳回来源页或管理后台。
 * 路由参数（query）：redirect 登录成功后希望返回的站内路径（由路由守卫写入）。
 * 数据来源：useAuthStore().login（内部请求后端认证接口）。
 * 交互流程：表单提交 → 校验失败或接口报错时展示 error → 成功则按 redirectTarget 跳转。
 */
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const submitting = ref(false)
const error = ref('')

// 只接受以单个 / 开头的站内路径，排除 //evil.com 这类协议相对地址，防止开放重定向
function redirectTarget() {
  const target = String(route.query.redirect || '')
  if (target.startsWith('/') && !target.startsWith('//')) {
    return target
  }
  return auth.isAdmin ? '/admin' : '/'
}

// submitting 兼作防重复提交锁：请求期间按钮被禁用，这里再兜底一次
async function submit() {
  if (submitting.value) {
    return
  }
  submitting.value = true
  error.value = ''
  try {
    // 登录成功后 auth 已写入登录态，再决定落地页：有 redirect 优先，否则管理员进后台
    await auth.login(username.value.trim(), password.value)
    await router.push(redirectTarget())
  } catch (e) {
    error.value = e.message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page auth-page">
    <div class="auth-card">
      <p class="eyebrow">欢迎回来</p>
      <h1>登录</h1>
      <p class="muted">登录后可以发表评论，管理员可以进入管理后台。</p>

      <!-- 登录表单：基础长度校验交给浏览器原生属性，接口错误由 alert 展示 -->
      <form class="form" @submit.prevent="submit">
        <label>
          <span>用户名</span>
          <input v-model="username" autocomplete="username" required minlength="3" maxlength="32" />
        </label>
        <label>
          <span>密码</span>
          <input v-model="password" type="password" autocomplete="current-password" required minlength="6" />
        </label>
        <p v-if="error" class="alert alert-error">{{ error }}</p>
        <button class="btn btn-primary btn-block" type="submit" :disabled="submitting">
          {{ submitting ? '登录中…' : '登录' }}
        </button>
      </form>

      <!-- 切换到注册时透传 query，保留 redirect 以便注册后回到原页面 -->
      <p class="auth-alt">
        还没有账号？
        <RouterLink :to="{ name: 'register', query: route.query }">注册一个</RouterLink>
      </p>
    </div>
  </div>
</template>