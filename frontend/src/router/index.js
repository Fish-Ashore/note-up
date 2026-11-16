/**
 * 路由配置与访问控制。
 * 依赖 auth store 判断登录/管理员身份；页面组件全部懒加载以减小首屏体积。
 * 守卫流程：确保登录态已恢复 → 校验 requiresAdmin 元信息 → 必要时跳转登录或首页。
 */
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

// 使用 History 模式，URL 不含 #，需服务端配置 fallback 到 index.html
const routes = [
  {
    path: '/',
    name: 'home',
    component: () => import('../pages/Home.vue')
  },
  {
    // 笔记以 slug 作为对外标识，而非数据库主键，便于阅读与分享
    path: '/notes/:slug',
    name: 'note',
    component: () => import('../pages/NoteDetail.vue')
  },
  {
    path: '/search',
    name: 'search',
    component: () => import('../pages/Search.vue')
  },
  {
    path: '/tags',
    name: 'tag',
    component: () => import('../pages/TagNotes.vue')
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('../pages/Login.vue')
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('../pages/Register.vue')
  },
  {
    // 管理区使用布局组件承载侧边栏，子路由渲染在 AdminLayout 的出口中
    path: '/admin',
    component: () => import('../layouts/AdminLayout.vue'),
    // meta 由全局守卫读取，统一拦截非管理员
    meta: { requiresAdmin: true },
    children: [
      {
        // 空路径对应 /admin 本身
        path: '',
        name: 'admin-dashboard',
        component: () => import('../pages/admin/Dashboard.vue')
      },
      {
        path: 'comments',
        name: 'admin-comments',
        component: () => import('../pages/admin/Comments.vue')
      },
      {
        path: 'users',
        name: 'admin-users',
        component: () => import('../pages/admin/Users.vue')
      },
      {
        path: 'notes',
        name: 'admin-notes',
        component: () => import('../pages/admin/Notes.vue')
      }
    ]
  },
  {
    // 兜底路由必须放在最后，否则会抢先匹配其他路径
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('../pages/NotFound.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, from, savedPosition) {
    // 浏览器前进/后退时恢复原滚动位置，体验更自然
    if (savedPosition) {
      return savedPosition
    }
    // 带锚点的跳转（如目录、wikilink）平滑滚动到目标元素
    if (to.hash) {
      return { el: to.hash, behavior: 'smooth' }
    }
    return { top: 0 }
  }
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  // 首次进入或刷新时 store 尚未恢复，先等待 /auth/me 完成再判断权限
  if (!auth.ready) {
    await auth.restore()
  }
  if (to.meta.requiresAdmin && !auth.isAdmin) {
    // 未登录：携带原路径，登录成功后可跳回；已登录但非管理员：回首页
    if (!auth.isLoggedIn) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    return { name: 'home' }
  }
  return true
})

router.afterEach((to) => {
  // 路由名到页面标题的映射；详情页未在此表中，单独兜底为“阅读”
  const titles = {
    home: '笔记',
    search: '搜索',
    login: '登录',
    register: '注册',
    'admin-dashboard': '管理控制台',
    'admin-comments': '评论审核',
    'admin-users': '用户管理',
    'admin-notes': '索引排查'
  }
  const name = titles[to.name] || (to.name === 'note' ? '阅读' : '')
  document.title = name ? `${name} · Note-Up` : 'Note-Up'
})

export default router