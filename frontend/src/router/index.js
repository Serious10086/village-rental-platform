import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { usePermissionStore } from '@/stores/permission'

/**
 * 路由配置。
 *
 * 【静态路由 vs 动态路由】
 *   · 静态路由(这里写死的)—— 前台门户、登录页、404。
 *     它们的页面清单是固定的,不随用户身份变化。
 *   · 动态路由(后端下发)—— 全部后台页面 /admin/**。
 *     由 stores/permission.js 在登录后按角色动态注册。
 *
 * 后台路由之所以不写死,是因为写死就等于把页面清单打进了 JS 包里,
 * 用户在地址栏手敲 URL 就能进 —— 菜单藏起来只是藏了入口,不是拦住了访问。
 */
const routes = [
  {
    path: '/',
    component: () => import('@/layout/FrontLayout.vue'),
    children: [
      {
        path: '',
        name: 'Home',
        component: () => import('@/views/front/Home.vue'),
        meta: { title: '首页' }
      },
      {
        path: 'resource',
        name: 'ResourceList',
        component: () => import('@/views/front/ResourceList.vue'),
        meta: { title: '浏览资源' }
      },
      {
        path: 'resource/:id',
        name: 'ResourceDetail',
        component: () => import('@/views/front/ResourceDetail.vue'),
        meta: { title: '资源详情' }
      },
      // 留言板和公告都是<b>公开页面</b> —— 不标 requiresAuth。
      // 公告要让访客在登录之前就看见这个村在做什么;留言板的浏览
      // 同样不设门槛,只有发帖/点赞/回复才需要登录。
      // 后端的白名单(application.yml 的 app.security.whitelist)
      // 与这里是一一对应的,两边必须一起改。
      {
        path: 'board',
        name: 'Board',
        component: () => import('@/views/front/Board.vue'),
        meta: { title: '村民留言板' }
      },
      {
        path: 'announcement',
        name: 'Announcement',
        component: () => import('@/views/front/Announcement.vue'),
        meta: { title: '村务公告' }
      },
      {
        path: 'announcement/:id(\\d+)',
        name: 'AnnouncementDetail',
        component: () => import('@/views/front/AnnouncementDetail.vue'),
        meta: { title: '公告详情' }
      },
      {
        path: 'publish',
        name: 'Publish',
        component: () => import('@/views/front/Publish.vue'),
        meta: { title: '发布资源', requiresAuth: true }
      },
      {
        path: 'publish/edit/:id',
        name: 'PublishEdit',
        component: () => import('@/views/front/Publish.vue'),
        meta: { title: '编辑资源', requiresAuth: true }
      },
      {
        path: 'order/create/:resourceId',
        name: 'OrderCreate',
        component: () => import('@/views/front/OrderCreate.vue'),
        meta: { title: '确认订单', requiresAuth: true }
      },
      {
        path: 'order/:id(\\d+)',
        name: 'OrderDetail',
        component: () => import('@/views/front/OrderDetail.vue'),
        meta: { title: '订单详情', requiresAuth: true }
      },
      {
        path: 'my/resource',
        name: 'MyResources',
        component: () => import('@/views/front/MyResources.vue'),
        meta: { title: '我的发布', requiresAuth: true }
      },
      {
        path: 'my/order',
        name: 'MyOrders',
        component: () => import('@/views/front/MyOrders.vue'),
        meta: { title: '我的订单', requiresAuth: true }
      },
      {
        path: 'my/credit',
        name: 'MyCredit',
        component: () => import('@/views/front/MyCredit.vue'),
        meta: { title: '我的信用', requiresAuth: true }
      },
      {
        path: 'my/dispute',
        name: 'MyDispute',
        component: () => import('@/views/front/MyDispute.vue'),
        meta: { title: '我的纠纷', requiresAuth: true }
      },
      {
        path: 'user/wallet',
        name: 'Wallet',
        component: () => import('@/views/front/Wallet.vue'),
        meta: { title: '我的钱包', requiresAuth: true }
      },
      {
        path: 'messages',
        name: 'Messages',
        component: () => import('@/views/front/Messages.vue'),
        meta: { title: '消息', requiresAuth: true }
      },
      {
        // 智能客服要登录:对话记录挂在人身上,提示词里带村组,
        // 而且它是按量计费的外部服务 —— 不能开放给游客
        path: 'ai',
        name: 'AiChat',
        component: () => import('@/views/front/AiChat.vue'),
        meta: { title: '智能客服', requiresAuth: true }
      },
      {
        // 收藏是"我的东西",要登录
        path: 'my/favorite',
        name: 'MyFavorites',
        component: () => import('@/views/front/MyFavorites.vue'),
        meta: { title: '我的收藏', requiresAuth: true }
      },
      {
        path: 'user/profile',
        name: 'Profile',
        component: () => import('@/views/front/Profile.vue'),
        meta: { title: '个人中心', requiresAuth: true }
      },
      {
        path: 'notice',
        name: 'Notice',
        component: () => import('@/views/front/Notice.vue'),
        meta: { title: '消息通知', requiresAuth: true }
      }
    ]
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/Login.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/front/NotFound.vue'),
    meta: { title: '页面不存在' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, from, savedPosition) {
    return savedPosition || { top: 0 }
  }
})

/**
 * 判断该路由是否需要登录。
 *
 * 【为什么不用"给路由打 meta.public 标记"那种反向写法】
 *
 * 曾经的做法是在 404 兜底路由上标 `meta: { public: true }`,
 * 结果它**污染了所有未匹配的路径** —— 未登录用户访问 /admin/dashboard 时,
 * 这条路径先匹配到兜底路由,守卫读到的 to.meta.public 就是 true,
 * 于是误判为"公开页面"直接放行,用户看到的是一个令人困惑的 404。
 *
 * 现在改成"需要登录"显式标注(opt-in),规则只有两条:
 *   1. 后台路径一律需要登录
 *   2. 前台页面自己标 meta.requiresAuth
 * 没标注的就是公开页面 —— 兜底路由天然不会被误判。
 */
function requiresLogin(to) {
  return to.path.startsWith('/admin') || to.meta?.requiresAuth === true
}

router.beforeEach(async (to, from, next) => {
  const userStore = useUserStore()
  const permissionStore = usePermissionStore()

  // ── 1. 已登录用户访问登录页 → 直接送走,不要让他再登一次 ──
  if (to.path === '/login') {
    if (userStore.isLoggedIn) {
      next(userStore.hasAdminAccess ? '/admin/dashboard' : '/')
    } else {
      next()
    }
    return
  }

  // ── 2. 未登录:需要登录的送去登录页并记住原目标,其余放行 ──
  if (!userStore.isLoggedIn) {
    if (requiresLogin(to)) {
      next({ path: '/login', query: { redirect: to.fullPath } })
    } else {
      next()
    }
    return
  }

  // ── 3. 已登录但本地没有用户信息 ──
  //     刷新页面时会出现:token 在 localStorage,userInfo 还没拉。
  //     这里补拉一次,失败(比如 token 过期)就清掉登录态。
  if (!userStore.userInfo) {
    try {
      await userStore.fetchUserInfo()
    } catch (e) {
      userStore.clearAuth()
      permissionStore.reset()
      next({ path: '/login', query: { redirect: to.fullPath } })
      return
    }
  }

  // ── 4. 访问后台:没有后台权限的角色(村民/买家)送回前台 ──
  if (to.path.startsWith('/admin') && !userStore.hasAdminAccess) {
    next('/')
    return
  }

  // ── 5. 访问后台但动态路由还没注册 → 拉取、注册、重新进入 ──
  if (to.path.startsWith('/admin') && !permissionStore.routesLoaded) {
    try {
      const dynamicRoutes = await permissionStore.generateRoutes()
      dynamicRoutes.forEach((route) => router.addRoute(route))

      // 重新导航到目标地址,让刚注册的路由生效。
      //
      // 【必须用 path 而不是 { ...to }】
      // 404 兜底路由是静态注册的,这次导航的 to.name 已经被解析成了 'NotFound',
      // 而 Vue Router 解析目标地址时**优先用 name** —— 写成 next({ ...to })
      // 会带着 name='NotFound' 又跳回 404,刚注册的后台路由永远轮不上。
      next({ path: to.fullPath, replace: true })
    } catch (e) {
      // 用 warn 而不是 error:token 过期后重新打开页面就会走到这里,
      // 这是正常流程,不该在控制台里显示成错误
      console.warn('动态路由加载失败,已跳回登录页:', e?.message || e)
      userStore.clearAuth()
      permissionStore.reset()
      next({ path: '/login' })
    }
    return
  }

  next()
})

// 设置页面标题
router.afterEach((to) => {
  const base = '乡村闲置资源租售平台'
  document.title = to.meta?.title ? `${to.meta.title} - ${base}` : base
})

export default router
