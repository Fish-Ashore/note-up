<template>
  <!-- 404 兜底页：由路由 /:pathMatch(.*)* 匹配，只负责提示并提供回首页入口 -->
  <div class="page empty-state">
    <p class="eyebrow">404</p>
    <h1>页面不存在</h1>
    <p class="muted">你访问的地址可能已经移动或被删除。</p>
    <RouterLink class="btn btn-primary" to="/">回到首页</RouterLink>
  </div>
</template>