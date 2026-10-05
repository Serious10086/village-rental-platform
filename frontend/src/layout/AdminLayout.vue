<script setup>
/**
 * 后台布局骨架:左侧菜单 + 顶部信息栏 + 内容区。
 *
 * 动态路由的顶层节点都挂在这个骨架上,所以侧边栏菜单树
 * 和路由表是同一份数据(permissionStore.dynamicRoutes),
 * 不会出现"菜单里有但点进去 404"的不一致。
 */
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { usePermissionStore } from '@/stores/permission'
import SidebarItem from './SidebarItem.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const permissionStore = usePermissionStore()

const collapsed = ref(false)

/** 侧边栏当前高亮的菜单项 */
const activeMenu = computed(() => route.path)

/** 面包屑:当前路由的匹配链 */
const breadcrumbs = computed(() =>
  route.matched
    .filter((r) => r.meta?.title)
    .map((r) => ({ title: r.meta.title, path: r.path }))
)

/** 角色显示名 */
const roleLabel = computed(() => {
  if (userStore.isSuperAdmin) return '超级管理员'
  if (userStore.roles.has('VILLAGE_ADMIN')) return '村级管理员'
  if (userStore.roles.has('VILLAGER')) return '资源发布者'
  if (userStore.roles.has('BUYER')) return '资源使用者'
  return '用户'
})

/** 数据范围提示 —— 让管理员清楚自己看到的是哪一部分数据 */
const scopeLabel = computed(() => {
  const map = { 1: '全部数据', 2: '本镇数据', 3: '本村数据', 4: '仅本人数据' }
  return map[userStore.userInfo?.dataScope] || ''
})

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗?', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch (e) {
    return // 用户点了取消
  }
  await userStore.logout()
  permissionStore.reset()
  ElMessage.success('已退出登录')
  router.push('/login')
}

function goProfile() {
  router.push('/user/profile')
}
</script>

<template>
  <el-container class="admin-layout">
    <!-- ── 侧边栏 ──────────────────────────────────────────── -->
    <el-aside :width="collapsed ? 'var(--sidebar-width-collapsed)' : 'var(--sidebar-width)'" class="sidebar">
      <div class="logo" :class="{ 'is-collapsed': collapsed }">
        <div class="logo-mark">
          <el-icon :size="20"><OfficeBuilding /></el-icon>
        </div>
        <span v-show="!collapsed" class="logo-text">闲置资源平台</span>
      </div>

      <el-scrollbar class="menu-scroll">
        <el-menu
          :default-active="activeMenu"
          :collapse="collapsed"
          :collapse-transition="false"
          router
          class="sidebar-menu"
        >
          <SidebarItem :items="permissionStore.dynamicRoutes" />
        </el-menu>
      </el-scrollbar>

      <!-- 空菜单提示:村民/买家登录后台时会看到 -->
      <div v-if="permissionStore.dynamicRoutes.length === 0" class="empty-menu">
        <el-empty :image-size="60" description="没有可访问的后台菜单" />
      </div>
    </el-aside>

    <el-container>
      <!-- ── 顶栏 ──────────────────────────────────────────── -->
      <el-header class="header">
        <div class="header-left">
          <el-icon class="collapse-btn" :size="20" @click="collapsed = !collapsed">
            <Fold v-if="!collapsed" />
            <Expand v-else />
          </el-icon>

          <el-breadcrumb separator="/">
            <el-breadcrumb-item v-for="(item, idx) in breadcrumbs" :key="idx">
              {{ item.title }}
            </el-breadcrumb-item>
          </el-breadcrumb>

          <!-- 数据范围标签:答辩演示数据级权限时一眼可见 -->
          <el-tag v-if="scopeLabel" size="small" type="info" effect="plain" class="scope-tag">
            <el-icon><View /></el-icon>
            {{ scopeLabel }}
          </el-tag>
        </div>

        <div class="header-right">
          <el-tooltip content="返回前台门户" placement="bottom">
            <el-icon :size="18" class="header-icon" @click="router.push('/')">
              <HomeFilled />
            </el-icon>
          </el-tooltip>

          <el-dropdown trigger="click">
            <div class="user-info">
              <el-avatar :size="30" :src="userStore.userInfo?.avatar">
                {{ (userStore.userInfo?.realName || userStore.userInfo?.phone || '?').charAt(0) }}
              </el-avatar>
              <div class="user-text">
                <span class="user-name">
                  {{ userStore.userInfo?.realName || userStore.userInfo?.phone }}
                </span>
                <span class="user-role">{{ roleLabel }}</span>
              </div>
              <el-icon><ArrowDown /></el-icon>
            </div>

            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>
                  <span class="text-xs">
                    {{ userStore.userInfo?.villageName || '未绑定村组' }}
                  </span>
                </el-dropdown-item>
                <el-dropdown-item divided @click="goProfile">
                  <el-icon><User /></el-icon> 个人中心
                </el-dropdown-item>
                <el-dropdown-item @click="router.push('/')">
                  <el-icon><HomeFilled /></el-icon> 前台门户
                </el-dropdown-item>
                <el-dropdown-item divided @click="handleLogout">
                  <el-icon><SwitchButton /></el-icon> 退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- ── 内容区 ────────────────────────────────────────── -->
      <el-main class="main">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin-layout {
  height: 100%;
}

/* ── 侧边栏 ──────────────────────────────────────────────── */
.sidebar {
  background: #0f172a;
  display: flex;
  flex-direction: column;
  transition: width var(--duration-normal) var(--ease-out);
  overflow: hidden;
}

.logo {
  height: var(--header-height);
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: 0 var(--space-md);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  flex-shrink: 0;
}

.logo.is-collapsed {
  justify-content: center;
  padding: 0;
}

.logo-mark {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  background: var(--color-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.logo-text {
  color: #fff;
  font-weight: var(--font-weight-bold);
  font-size: var(--font-size-base);
  white-space: nowrap;
}

.menu-scroll {
  flex: 1;
}

.sidebar-menu {
  border-right: none;
  background: transparent;
  padding: var(--space-sm) 0;
}

/* 深色侧边栏需要覆盖 Element Plus 的默认浅色样式 */
.sidebar-menu :deep(.el-menu-item),
.sidebar-menu :deep(.el-sub-menu__title) {
  color: rgba(255, 255, 255, 0.72);
  height: 44px;
  line-height: 44px;
  margin: 2px var(--space-sm);
  border-radius: var(--radius-sm);
}

.sidebar-menu :deep(.el-menu-item:hover),
.sidebar-menu :deep(.el-sub-menu__title:hover) {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
}

.sidebar-menu :deep(.el-menu-item.is-active) {
  background: var(--color-primary);
  color: #fff;
  font-weight: var(--font-weight-medium);
}

.sidebar-menu :deep(.el-sub-menu .el-menu) {
  background: transparent;
}

.sidebar-menu :deep(.el-sub-menu .el-menu-item) {
  padding-left: 44px !important;
}

.empty-menu {
  padding: var(--space-md);
}

.empty-menu :deep(.el-empty__description p) {
  color: rgba(255, 255, 255, 0.45);
  font-size: var(--font-size-xs);
}

/* ── 顶栏 ────────────────────────────────────────────────── */
.header {
  height: var(--header-height);
  background: var(--color-bg-card);
  border-bottom: 1px solid var(--color-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--space-lg);
}

.header-left {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  min-width: 0;
}

.collapse-btn {
  cursor: pointer;
  color: var(--color-text-secondary);
  transition: color var(--duration-fast);
  flex-shrink: 0;
}

.collapse-btn:hover {
  color: var(--color-primary);
}

.scope-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.header-right {
  display: flex;
  align-items: center;
  gap: var(--space-lg);
}

.header-icon {
  cursor: pointer;
  color: var(--color-text-secondary);
  transition: color var(--duration-fast);
}

.header-icon:hover {
  color: var(--color-primary);
}

.user-info {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  transition: background var(--duration-fast);
}

.user-info:hover {
  background: var(--color-info-light);
}

.user-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.user-name {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
}

.user-role {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* ── 内容区 ──────────────────────────────────────────────── */
.main {
  background: var(--color-bg-page);
  padding: var(--space-lg);
  overflow-y: auto;
}

/* ── 响应式:窄屏默认收起侧边栏 ──────────────────────────── */
@media (max-width: 768px) {
  .sidebar {
    width: var(--sidebar-width-collapsed) !important;
  }

  .header {
    padding: 0 var(--space-md);
  }

  .user-text {
    display: none;
  }

  .scope-tag {
    display: none;
  }
}
</style>
