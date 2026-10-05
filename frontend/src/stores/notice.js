import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as auditApi from '@/api/audit'
import { useUserStore } from '@/stores/user'

/**
 * 通知状态。
 *
 * 未读数需要在前端持续可见(顶栏红点),所以单独抽一个 store ——
 * 放在组件里的话,布局组件和通知页各持有一份,标记已读后另一处不会更新。
 */
export const useNoticeStore = defineStore('notice', () => {
  const unreadCount = ref(0)
  const notices = ref([])
  const loading = ref(false)

  /** 轮询定时器。用普通变量而不是 ref —— 它不需要被模板响应式追踪 */
  let timer = null

  const hasUnread = computed(() => unreadCount.value > 0)

  /** 拉取未读数 */
  async function fetchUnread() {
    const userStore = useUserStore()
    // 未登录时不请求 —— 否则每次打开页面都会收到一个 401
    if (!userStore.isLoggedIn) {
      unreadCount.value = 0
      return
    }
    try {
      unreadCount.value = await auditApi.getUnreadCount()
    } catch (e) {
      // 拉取失败不打扰用户,红点保持上一次的值即可
    }
  }

  /** 拉取通知列表 */
  async function fetchList(params = {}) {
    loading.value = true
    try {
      const data = await auditApi.getNoticeList({ pageNum: 1, pageSize: 20, ...params })
      notices.value = data.rows || []
      return data
    } finally {
      loading.value = false
    }
  }

  /** 标记单条已读 */
  async function markRead(id) {
    await auditApi.markNoticeRead(id)
    const item = notices.value.find((n) => n.id === id)
    if (item && !item.isRead) {
      item.isRead = 1
      unreadCount.value = Math.max(0, unreadCount.value - 1)
    }
  }

  /** 全部已读 */
  async function markAllRead() {
    await auditApi.markAllNoticeRead()
    notices.value.forEach((n) => { n.isRead = 1 })
    unreadCount.value = 0
  }

  async function remove(id) {
    await auditApi.deleteNotice(id)
    const index = notices.value.findIndex((n) => n.id === id)
    if (index >= 0) {
      if (!notices.value[index].isRead) {
        unreadCount.value = Math.max(0, unreadCount.value - 1)
      }
      notices.value.splice(index, 1)
    }
  }

  /**
   * 开始轮询未读数。
   *
   * 用轮询而不是 WebSocket:通知的实时性要求不高(晚 30 秒看到没有影响),
   * 而 WebSocket 要处理断线重连、心跳、连接鉴权,复杂度不成比例。
   */
  function startPolling(intervalMs = 30000) {
    stopPolling()
    fetchUnread()
    timer = setInterval(fetchUnread, intervalMs)
  }

  function stopPolling() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  /** 退出登录时重置 */
  function reset() {
    stopPolling()
    unreadCount.value = 0
    notices.value = []
  }

  return {
    unreadCount,
    notices,
    loading,
    hasUnread,
    fetchUnread,
    fetchList,
    markRead,
    markAllRead,
    remove,
    startPolling,
    stopPolling,
    reset
  }
})
