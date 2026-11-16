<script setup>
/**
 * 注册页（路由 name: register，路径 /register）
 *
 * 职责：收集用户名、密码与确认密码，前端校验通过后注册并自动登录。
 * 路由参数（query）：redirect 注册成功后的站内跳转目标（来自登录页/路由守卫的透传）。
 * 数据来源：useAuthStore().register（内部请求后端注册接口）。
 * 交互流程：提交 → 三项本地校验 → 调用注册 → 成功跳转，失败展示服务端消息。
 */
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const confirm = ref('')
const submitting = ref(false)
const error = ref('')

// 与后端保持一致的规则先行校验，减少无意义的请求往返；错误只保留第一条
async function submit() {
  if (submitting.value) {
    return
  }
  error.value = ''
  if (!/^[A-Za-z0-9_-]{3,32}$/.test(username.value.trim())) {
    error.value = '用户名需为 3-32 位字母、数字、下划线或短横线'
    return
  }
  if (password.value.length < 6) {
    error.value = '密码至少 6 位'
    return
  }
  if (password.value !== confirm.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  submitting.value = true
  try {
    // 注册接口会直接返回登录态，因此成功后无需再走一次登录
    await auth.register(username.value.trim(), password.value)
    // 同样限制为站内单斜杠路径，避免 redirect 被用作跳转到外站
    const target = String(route.query.redirect || '')
    await router.push(target.startsWith('/') && !target.startsWith('//') ? target : '/')
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
      <p class="eyebrow">新读者</p>
      <h1>注册</h1>
      <p class="muted">注册账号固定为普通用户，发表评论后需要管理员审核。</p>

      <!-- 注册表单：提示文案与校验规则对应，提交期间按钮禁用 -->
      <form class="form" @submit.prevent="submit">
        <label>
          <span>用户名</span>
          <input v-model="username" autocomplete="username" required minlength="3" maxlength="32" />
          <small class="muted">3-32 位字母、数字、下划线或短横线</small>
        </label>
        <label>
          <span>密码</span>
          <input v-model="password" type="password" autocomplete="new-password" required minlength="6" />
        </label>
        <label>
          <span>确认密码</span>
          <input v-model="confirm" type="password" autocomplete="new-password" required minlength="6" />
        </label>
        <p v-if="error" class="alert alert-error">{{ error }}</p>
        <button class="btn btn-primary btn-block" type="submit" :disabled="submitting">
          {{ submitting ? '注册中…' : '注册并登录' }}
        </button>
      </form>

      <!-- 已有账号时跳转登录，并透传 redirect 保持最终落地页一致 -->
      <p class="auth-alt">
        已有账号？
        <RouterLink :to="{ name: 'login', query: route.query }">直接登录</RouterLink>
      </p>
    </div>
  </div>
</template>