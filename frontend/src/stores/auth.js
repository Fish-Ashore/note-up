/**
 * 认证状态仓库：维护 token、当前用户与初始化标记。
 * 数据流：登录/注册接口返回 { token, user } → apply 写入 state 并持久化 token；
 *        刷新页面后 restore 用本地 token 请求 /auth/me 还原 user。
 * 依赖 api/client 的 token 存取；route 守卫与各组件共用同一份登录态。
 */
import { defineStore } from 'pinia'
import { api, getToken, setToken } from '../api/client'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    // 初始 token 直接读 localStorage，保证首屏请求即可携带凭证
    token: getToken(),
    user: null,
    // ready 表示“是否已尝试恢复登录态”，用于避免重复请求 /auth/me
    ready: false
  }),
  getters: {
    // 以 user 是否存在判定登录，而非 token：token 失效时 user 会被清空
    isLoggedIn: (state) => Boolean(state.user),
    isAdmin: (state) => state.user?.role === 'ADMIN',
    username: (state) => state.user?.username || ''
  },
  actions: {
    /**
     * 恢复登录态，幂等：ready 为 true 时直接返回。
     * 无 token 时只需置 ready；有 token 但校验失败则清理本地凭证。
     */
    async restore() {
      if (this.ready) {
        return
      }
      if (!this.token) {
        this.ready = true
        return
      }
      try {
        this.user = await api.get('/auth/me')
      } catch {
        // token 过期或伪造，静默登出，不向用户抛错
        this.clear()
      } finally {
        // 无论成功失败都标记完成，防止守卫反复等待
        this.ready = true
      }
    },
    async login(username, password) {
      // 登录接口本身不需要鉴权头
      const result = await api.post('/auth/login', { username, password }, { auth: false })
      this.apply(result)
      return result
    },
    async register(username, password) {
      const result = await api.post('/auth/register', { username, password }, { auth: false })
      this.apply(result)
      return result
    },
    // 统一落地一次成功认证的结果，避免 login/register 重复代码
    apply(result) {
      this.token = result.token
      this.user = result.user
      this.ready = true
      setToken(result.token)
    },
    logout() {
      this.clear()
    },
    clear() {
      // 同时清理内存状态与 localStorage，确保刷新后不会“复活”
      this.user = null
      this.token = ''
      setToken('')
    }
  }
})