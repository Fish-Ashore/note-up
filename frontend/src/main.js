/**
 * 应用入口：创建 Vue 实例，装配 Pinia 与路由后挂载到 index.html 的 #app。
 * 执行顺序有依赖：先装 Pinia，路由守卫中才能在任意页面安全地使用 auth store。
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './style.css'

const app = createApp(App)
// Pinia 必须先于 router 注册，避免路由守卫首次执行时拿不到 store 实例
app.use(createPinia())
app.use(router)
app.mount('#app')