---
title: Vue 3 状态管理
slug: vue-state
publish: true
category: 前端
tags: [vue, pinia, 前端, 状态管理]
created: 2026-09-21
updated: 2026-10-23
---

# Vue 3 状态管理

前端只保存两类状态：认证信息和页面数据。认证信息放进 Pinia，页面数据尽量留在组件内，避免全局状态膨胀。

## 认证 Store

```js
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('noteup_token') || '',
    user: null,
    ready: false
  }),
  getters: {
    isLoggedIn: (state) => Boolean(state.user),
    isAdmin: (state) => state.user?.role === 'ADMIN'
  },
  actions: {
    async restore() {
      if (!this.token) {
        this.ready = true
        return
      }
      try {
        this.user = await api.get('/auth/me')
      } catch {
        this.clear()
      } finally {
        this.ready = true
      }
    }
  }
})
```

## 路由守卫

```js
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.ready) await auth.restore()
  if (to.meta.requiresAdmin && !auth.isAdmin) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
})
```

> [!note] 为什么要 `ready`
> 刷新页面时，token 已存在但用户信息还没拉取。如果不等待 `restore()`，管理员会被误判为游客并跳回首页。

| 状态 | 位置 | 生命周期 |
|---|---|---|
| token | localStorage + Pinia | 跨刷新 |
| user | Pinia | 刷新后从 `/auth/me` 恢复 |
| 笔记列表 | 页面组件 | 页面级 |
| 评论草稿 | 页面组件 | 页面级 |

详情页的渲染流程见 [[Markdown 渲染管线]]。#vue #前端