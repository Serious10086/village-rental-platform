import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as authApi from '@/api/auth'

/**
 * 权限路由 —— 后台的动态路由注册。
 *
 * 【为什么必须动态注册,不能写死静态路由再靠菜单隐藏】
 *
 * 如果后台路由在打包时就全部写进 JS,那么任何人都能在地址栏手敲 URL
 * 进到任意后台页面 —— 菜单藏起来只是"看不见入口",不是"进不去"。
 *
 * 动态注册则是由后端根据角色决定"这个用户有哪些路由",前端只注册
 * 后端给的那部分。没被授权的路由压根不存在,手敲 URL 会落到 404。
 * 这才是菜单级权限的实质。
 */

/**
 * 预扫描所有后台页面组件。
 *
 * Vite 要求 import.meta.glob 的参数是字面量字符串,不能用变量拼接,
 * 所以这里一次性把 views/admin 下所有 .vue 都登记进来,
 * 后面按后端返回的路径去查表。
 */
const viewModules = import.meta.glob('@/views/admin/**/*.vue')

export const usePermissionStore = defineStore('permission', () => {
  /** 已生成的后台路由 */
  const dynamicRoutes = ref([])

  /** 是否已加载过 —— 路由守卫用它判断要不要重新拉取 */
  const routesLoaded = ref(false)

  /**
   * 把后端返回的组件路径解析成真正的组件。
   *
   * @param componentPath 如 'admin/user/index';顶层目录固定为 'Layout'
   */
  function resolveComponent(componentPath) {
    if (!componentPath) return undefined

    // 顶层目录挂载到后台布局骨架上
    if (componentPath === 'Layout') {
      return () => import('@/layout/AdminLayout.vue')
    }

    const key = `/src/views/${componentPath}.vue`
    if (viewModules[key]) {
      return viewModules[key]
    }

    // 页面还没开发时的兜底。
    //
    // 批次 1 只实现了认证,资源、订单等页面要到后面批次才有。
    // 有了这个兜底,菜单现在就能正常渲染和点击,不会因为组件不存在而白屏 ——
    // 也就不会阻塞"用菜单差异验证权限"这件事。
    console.warn(`[权限路由] 组件不存在: ${componentPath},将使用占位页`)
    return () => import('@/views/admin/Placeholder.vue')
  }

  /** 把后端的路由树转换成 vue-router 能用的配置 */
  function transformRoutes(routes, isTopLevel = true) {
    return routes.map((route) => {
      const children = route.children?.length
        ? transformRoutes(route.children, false)
        : null

      const meta = {
        title: route.meta?.title,
        icon: route.meta?.icon,
        hidden: route.meta?.hidden ?? false,
        perms: route.meta?.perms
      }

      // 顶层节点统一套一层后台布局,保证侧边栏和顶栏始终在。
      //
      // 【为什么需要这段】后端把"工作台"这类单页直接作为顶层节点下发,
      // 它的 component 是页面本身而不是 Layout。如果原样注册成顶层路由,
      // 这个页面会脱离后台布局渲染 —— 侧边栏、顶栏全部消失。
      // 这里给它补一层 AdminLayout 外壳,把原页面挂成唯一的子路由
      // (子路由 path 为空,表示"匹配父路径本身")。
      if (isTopLevel && !children) {
        return {
          path: route.path,
          component: () => import('@/layout/AdminLayout.vue'),
          children: [
            {
              path: '',
              name: route.name,
              component: resolveComponent(route.component),
              meta
            }
          ]
        }
      }

      const config = {
        path: route.path,
        name: route.name,
        component: resolveComponent(route.component),
        meta
      }
      if (children) {
        config.children = children
        // 目录路径本身没有页面,访问它应该跳到第一个子页面。
        //
        // 不加这句的话,访问 /admin/user 会渲染出"外壳有了、内容区空白"
        // 的尴尬画面 —— 因为父路由匹配上了,但没有任何子路由匹配。
        //
        // 取第一个子路由而不是硬编码某个页面:子菜单的顺序由后端
        // sort_order 决定,这里跟着走,不用两边各维护一份。
        const firstVisible = children.find((c) => !c.meta?.hidden)
        if (firstVisible) {
          config.redirect = firstVisible.path
        }
      }
      return config
    })
  }

  /**
   * 从后端拉取并生成本用户的动态路由。
   *
   * @returns 生成好的路由配置数组
   */
  async function generateRoutes() {
    const routes = await authApi.getRouters()
    const transformed = transformRoutes(routes || [])
    dynamicRoutes.value = transformed
    routesLoaded.value = true
    return transformed
  }

  /** 退出登录时重置 */
  function reset() {
    dynamicRoutes.value = []
    routesLoaded.value = false
  }

  return {
    dynamicRoutes,
    routesLoaded,
    generateRoutes,
    reset
  }
})
