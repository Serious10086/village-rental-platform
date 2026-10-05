import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as authApi from '@/api/auth'

/**
 * 用户状态 —— 登录态、用户信息、权限码。
 *
 * token 同时存在 localStorage 和这里:
 *   · localStorage 让刷新页面后仍是登录态(axios 拦截器也从这里读)
 *   · ref 让界面能响应式地跟着变
 * 两者必须同步更新,所以统一走 setToken / clearAuth,不要在外面直接改。
 */
export const useUserStore = defineStore('user', () => {
  // ── state ──────────────────────────────────────────────────
  const token = ref(localStorage.getItem('token') || '')
  const userInfo = ref(safeParse(localStorage.getItem('userInfo')))

  // ── getters ────────────────────────────────────────────────

  /** 是否已登录 */
  const isLoggedIn = computed(() => !!token.value)

  /**
   * 权限码集合。
   *
   * 用 Set 而不是数组:按钮显隐判断会执行很多次,
   * Set 的 has() 是 O(1),数组的 includes() 是 O(n)。
   */
  const perms = computed(() => new Set(userInfo.value?.perms || []))

  /** 角色编码集合 */
  const roles = computed(() => new Set(userInfo.value?.roles || []))

  /** 是否超级管理员 */
  const isSuperAdmin = computed(() => userInfo.value?.superAdmin === true)

  /** 是否已实名认证 —— 决定能否发布资源、下单 */
  const isRealnameVerified = computed(() => userInfo.value?.realnameVerified === true)

  /** 是否有后台访问权限(有角色就有菜单,村民/买家没有) */
  const hasAdminAccess = computed(
    () => roles.value.has('SUPER_ADMIN') || roles.value.has('VILLAGE_ADMIN')
  )

  // ── actions ────────────────────────────────────────────────

  /**
   * 判断是否拥有某个权限码。
   *
   * 超级管理员直接放行 —— 与后端 PermissionAspect 的逻辑保持一致,
   * 避免"后端放行了但前端把按钮藏起来了"这种前后端不一致的情况。
   */
  function hasPerm(perm) {
    if (!perm) return true
    if (isSuperAdmin.value) return true
    return perms.value.has(perm)
  }

  /** 是否拥有其中任意一个权限码 */
  function hasAnyPerm(permList) {
    if (!permList || permList.length === 0) return true
    return permList.some((p) => hasPerm(p))
  }

  function setToken(value) {
    token.value = value
    if (value) {
      localStorage.setItem('token', value)
    } else {
      localStorage.removeItem('token')
    }
  }

  function setUserInfo(info) {
    userInfo.value = info
    if (info) {
      localStorage.setItem('userInfo', JSON.stringify(info))
    } else {
      localStorage.removeItem('userInfo')
    }
  }

  /** 登录 */
  async function login(form) {
    const data = await authApi.login(form)
    setToken(data.token)
    setUserInfo(data.userInfo)
    return data.userInfo
  }

  /** 重新拉取用户信息(权限被管理员调整后调用) */
  async function fetchUserInfo() {
    const info = await authApi.getInfo()
    setUserInfo(info)
    return info
  }

  /** 清空登录态 */
  function clearAuth() {
    setToken('')
    setUserInfo(null)
  }

  /** 退出登录 */
  async function logout() {
    try {
      await authApi.logout()
    } catch (e) {
      // 退出接口失败也要清本地状态 —— 否则用户会卡在"退不出去"的状态
      console.warn('退出接口调用失败,已强制清理本地登录态', e)
    } finally {
      clearAuth()
    }
  }

  return {
    token,
    userInfo,
    isLoggedIn,
    perms,
    roles,
    isSuperAdmin,
    isRealnameVerified,
    hasAdminAccess,
    hasPerm,
    hasAnyPerm,
    login,
    fetchUserInfo,
    clearAuth,
    logout
  }
})

/** 安全解析 localStorage 里的 JSON —— 数据被手动改坏时不至于让整个应用崩掉 */
function safeParse(raw) {
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch (e) {
    console.warn('localStorage 中的 userInfo 解析失败,已忽略', e)
    return null
  }
}
