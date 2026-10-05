<script setup>
/**
 * 前台门户布局:顶部导航 + 内容区 + 页脚。
 *
 * 与后台布局的区别:后台是"侧边栏 + 权限驱动的菜单",
 * 前台是"固定导航 + 谁都能看"。所以两者不共用同一个布局组件 ——
 * 硬合并会变成一堆 v-if。
 */
import { computed, ref, onMounted, onUnmounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { usePermissionStore } from '@/stores/permission'
import { useNoticeStore } from '@/stores/notice'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const permissionStore = usePermissionStore()
const noticeStore = useNoticeStore()

const mobileMenuOpen = ref(false)

/** 当前激活的导航项 */
const activeNav = computed(() => {
  if (route.path === '/') return 'home'
  if (route.path.startsWith('/resource')) return 'resource'
  // /announcement 和 /board 是两个独立前缀,不互相干扰;
  // 但要排在 /notice 之前?不需要 —— startsWith('/notice') 匹配不上它们。
  // 留这两个分支在 /publish 之前,纯粹是按导航条上的顺序排。
  if (route.path.startsWith('/board')) return 'board'
  if (route.path.startsWith('/announcement')) return 'announcement'
  if (route.path.startsWith('/publish')) return 'publish'
  // 【顺序有讲究】越具体的路径要排得越靠前。
  // /my/order、/my/credit、/my/dispute 都有公共前缀 /my,
  // 把 /my 那条判断放在它们前面的话,这三个页面全都会高亮成"我的发布"。
  if (route.path.startsWith('/my/order') || route.path.startsWith('/order')) return 'order'
  if (route.path.startsWith('/my/credit')) return 'credit'
  if (route.path.startsWith('/my/dispute')) return 'dispute'
  if (route.path.startsWith('/user/wallet')) return 'wallet'
  if (route.path.startsWith('/messages')) return 'message'
  if (route.path.startsWith('/my')) return 'mine'
  if (route.path.startsWith('/notice')) return 'notice'
  return ''
})

const navItems = [
  { key: 'home', label: '首页', path: '/' },
  { key: 'resource', label: '浏览资源', path: '/resource' },
  // 留言板和公告是<b>公开</b>的,所以没有 requireLogin ——
  // 访客也能先看看这个村在聊什么、村里发了什么通知,
  // 再决定要不要注册。把它们藏到登录后面,等于把最有人气的内容藏起来。
  { key: 'board', label: '留言板', path: '/board' },
  { key: 'announcement', label: '村务公告', path: '/announcement' },
  { key: 'publish', label: '发布闲置', path: '/publish', requireLogin: true },
  { key: 'order', label: '我的订单', path: '/my/order', requireLogin: true },
  { key: 'favorite', label: '我的收藏', path: '/my/favorite', requireLogin: true },
  { key: 'credit', label: '我的信用', path: '/my/credit', requireLogin: true },
  { key: 'wallet', label: '我的钱包', path: '/user/wallet', requireLogin: true },
  // 智能客服要登录:对话记录挂在人身上、提示词里带村组,
  // 而且它是按量计费的外部服务,不能像留言板那样开放给访客随便问
  { key: 'ai', label: '智能客服', path: '/ai', requireLogin: true },
  // 导航里的"消息"是<b>站内信(与人对话)</b>,和右上角铃铛的
  // "系统通知"是两回事:一个是双向对话,一个是平台单向告知。
  // 两者混在一个页面里会让"我到底回没回他"变得看不出来。
  { key: 'message', label: '消息', path: '/messages', requireLogin: true }
]

function go(item) {
  mobileMenuOpen.value = false
  if (item.requireLogin && !userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    router.push({ path: '/login', query: { redirect: item.path } })
    return
  }
  router.push(item.path)
}

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗?', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  await userStore.logout()
  permissionStore.reset()
  ElMessage.success('已退出登录')
  router.push('/')
}

onMounted(() => {
  // 已登录但用户信息丢失(刷新后),补拉一次
  if (userStore.isLoggedIn && !userStore.userInfo) {
    userStore.fetchUserInfo().catch(() => userStore.clearAuth())
  }
  // 开始轮询未读通知数
  if (userStore.isLoggedIn) {
    noticeStore.startPolling()
  }
})

// 登录状态变化时开关轮询。
//
// 不处理的话会有两个问题:退出登录后轮询还在跑(每次请求都 401);
// 登录后不启动的话,要等下次刷新页面才看得到红点。
watch(
  () => userStore.isLoggedIn,
  (loggedIn) => {
    if (loggedIn) {
      noticeStore.startPolling()
    } else {
      noticeStore.reset()
    }
  }
)

// 组件卸载时停掉轮询。
// 布局组件通常和页面同生命周期,但严谨起见还是要清 ——
// 定时器不清会在热更新时越积越多。
onUnmounted(() => {
  noticeStore.stopPolling()
})
</script>

<template>
  <div class="front-layout">
    <!-- ── 顶部导航 ────────────────────────────────────────── -->
    <header class="navbar">
      <div class="page-container nav-inner">
        <div class="brand" @click="router.push('/')">
          <div class="brand-mark">
            <el-icon :size="20"><OfficeBuilding /></el-icon>
          </div>
          <span class="brand-text">乡村闲置资源租售平台</span>
        </div>

        <nav class="nav-links">
          <a
            v-for="item in navItems"
            :key="item.key"
            class="nav-link"
            :class="{ 'is-active': activeNav === item.key }"
            @click="go(item)"
          >
            {{ item.label }}
          </a>
        </nav>

        <div class="nav-right">
          <template v-if="userStore.isLoggedIn">
            <el-badge
              v-if="!userStore.isRealnameVerified"
              value="未实名"
              type="warning"
              class="realname-badge"
            />

            <!-- 通知铃铛:未读数由 notice store 轮询维护 -->
            <el-badge
              :value="noticeStore.unreadCount"
              :hidden="!noticeStore.hasUnread"
              :max="99"
              class="notice-badge"
            >
              <el-icon :size="19" class="header-icon" @click="router.push('/notice')">
                <Bell />
              </el-icon>
            </el-badge>

            <el-dropdown trigger="click">
              <div class="user-chip">
                <el-avatar :size="30" :src="userStore.userInfo?.avatar">
                  {{ (userStore.userInfo?.realName || userStore.userInfo?.phone || '?').charAt(0) }}
                </el-avatar>
                <span class="user-name">
                  {{ userStore.userInfo?.realName || userStore.userInfo?.phone }}
                </span>
                <el-icon><ArrowDown /></el-icon>
              </div>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="router.push('/user/profile')">
                    <el-icon><User /></el-icon> 个人中心
                  </el-dropdown-item>
                  <el-dropdown-item @click="router.push('/my/resource')">
                    <el-icon><Goods /></el-icon> 我的发布
                  </el-dropdown-item>
                  <el-dropdown-item @click="router.push('/my/order')">
                    <el-icon><Tickets /></el-icon> 我的订单
                  </el-dropdown-item>
                  <el-dropdown-item @click="router.push('/my/credit')">
                    <el-icon><Medal /></el-icon> 我的信用
                  </el-dropdown-item>
                  <el-dropdown-item @click="router.push('/user/wallet')">
                    <el-icon><Wallet /></el-icon> 我的钱包
                  </el-dropdown-item>
                  <el-dropdown-item @click="router.push('/my/dispute')">
                    <el-icon><WarningFilled /></el-icon> 我的纠纷
                  </el-dropdown-item>
                  <el-dropdown-item @click="router.push('/messages')">
                    <el-icon><ChatDotRound /></el-icon> 消息
                  </el-dropdown-item>
                  <el-dropdown-item @click="router.push('/notice')">
                    <el-icon><Bell /></el-icon> 系统通知
                  </el-dropdown-item>
                  <el-dropdown-item
                    v-if="userStore.hasAdminAccess"
                    divided
                    @click="router.push('/admin/dashboard')"
                  >
                    <el-icon><Setting /></el-icon> 后台管理
                  </el-dropdown-item>
                  <el-dropdown-item divided @click="handleLogout">
                    <el-icon><SwitchButton /></el-icon> 退出登录
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>

          <template v-else>
            <el-button text @click="router.push('/login')">登录</el-button>
            <el-button type="primary" @click="router.push('/login')">注册 / 登录</el-button>
          </template>

          <el-icon class="mobile-toggle" :size="22" @click="mobileMenuOpen = !mobileMenuOpen">
            <Menu />
          </el-icon>
        </div>
      </div>

      <!-- 移动端展开的菜单 -->
      <div v-show="mobileMenuOpen" class="mobile-menu">
        <a
          v-for="item in navItems"
          :key="item.key"
          class="mobile-link"
          :class="{ 'is-active': activeNav === item.key }"
          @click="go(item)"
        >
          {{ item.label }}
        </a>
      </div>
    </header>

    <!-- ── 内容区 ──────────────────────────────────────────── -->
    <main class="main">
      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>

    <!-- ── 页脚 ────────────────────────────────────────────── -->
    <footer class="footer">
      <div class="page-container footer-inner">
        <div class="footer-brand">
          <div class="brand-mark small">
            <el-icon :size="16"><OfficeBuilding /></el-icon>
          </div>
          <span>乡村闲置资源租售平台</span>
        </div>
        <p class="footer-desc">
          让村里的农机、场地、工具流转起来 —— 闲置的不该被浪费
        </p>
        <p class="footer-copy">
          西南财经大学天府学院 · 智能科技学院 · 计算机科学与技术 · 高浚峰
        </p>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.front-layout {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--color-bg-page);
}

/* ── 导航栏 ──────────────────────────────────────────────── */
.navbar {
  background: var(--color-bg-card);
  border-bottom: 1px solid var(--color-border);
  position: sticky;
  top: 0;
  z-index: 100;
}

.nav-inner {
  height: var(--header-height);
  display: flex;
  align-items: center;
  gap: var(--space-xl);
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  cursor: pointer;
  flex-shrink: 0;
}

.brand-mark {
  width: 34px;
  height: 34px;
  border-radius: var(--radius-sm);
  background: var(--color-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.brand-mark.small {
  width: 26px;
  height: 26px;
  border-radius: var(--radius-sm);
}

.brand-text {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  letter-spacing: -0.01em;
  white-space: nowrap;
}

.nav-links {
  display: flex;
  gap: var(--space-lg);
  flex: 1;
}

.nav-link {
  font-size: var(--font-size-base);
  color: var(--color-text-regular);
  cursor: pointer;
  padding: 4px 0;
  border-bottom: 2px solid transparent;
  transition: all var(--duration-fast) var(--ease-out);
  white-space: nowrap;
}

.nav-link:hover {
  color: var(--color-primary);
}

.nav-link.is-active {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
  font-weight: var(--font-weight-medium);
}

.nav-right {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-shrink: 0;
}

/* 未实名提醒徽标:实名认证后才能发布资源,提前提示比事后报错友好 */
.realname-badge {
  margin-right: var(--space-xs);
}

.realname-badge :deep(.el-badge__content) {
  font-size: 11px;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  transition: background var(--duration-fast);
}

.user-chip:hover {
  background: var(--color-info-light);
}

/* 通知铃铛 */
.notice-badge {
  cursor: pointer;
  margin-right: var(--space-sm);
}

.notice-badge :deep(.el-badge__content) {
  font-size: 11px;
}

.header-icon {
  color: var(--color-text-secondary);
  transition: color var(--duration-fast);
}

.header-icon:hover {
  color: var(--color-primary);
}

.user-name {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
}

.mobile-toggle {
  display: none;
  cursor: pointer;
  color: var(--color-text-secondary);
}

.mobile-menu {
  display: none;
  flex-direction: column;
  border-top: 1px solid var(--color-border);
  padding: var(--space-sm) var(--space-lg);
}

.mobile-link {
  padding: var(--space-sm) 0;
  color: var(--color-text-regular);
  cursor: pointer;
}

.mobile-link.is-active {
  color: var(--color-primary);
  font-weight: var(--font-weight-medium);
}

/* ── 内容与页脚 ──────────────────────────────────────────── */
.main {
  flex: 1;
  padding: var(--space-xl) 0;
}

.footer {
  background: var(--color-bg-card);
  border-top: 1px solid var(--color-border);
  padding: var(--space-xl) 0;
  margin-top: var(--space-2xl);
}

.footer-inner {
  text-align: center;
}

.footer-brand {
  display: inline-flex;
  align-items: center;
  gap: var(--space-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.footer-desc {
  margin-top: var(--space-sm);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.footer-copy {
  margin-top: var(--space-lg);
  padding-top: var(--space-md);
  border-top: 1px solid var(--color-border-light);
  color: var(--color-text-placeholder);
  font-size: var(--font-size-xs);
}

/* ── 响应式 ──────────────────────────────────────────────── */
@media (max-width: 900px) {
  .nav-links {
    display: none;
  }

  .mobile-toggle {
    display: block;
  }

  .mobile-menu {
    display: flex;
  }

  .brand-text {
    font-size: var(--font-size-sm);
  }

  .user-name {
    display: none;
  }
}
</style>
