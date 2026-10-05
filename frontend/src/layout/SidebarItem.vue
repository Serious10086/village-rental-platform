<script setup>
/**
 * 侧边栏菜单项(递归组件)。
 *
 * 菜单树来自后端下发的动态路由,所以"谁能看到哪些菜单"完全由服务端决定,
 * 前端不做任何过滤 —— 前端过滤意味着数据也下发了,那就不算权限控制。
 */
import { computed } from 'vue'

const props = defineProps({
  /** 本层要渲染的菜单项 */
  items: { type: Array, required: true },
  /** 父级路径,用于计算子项的完整路径 */
  basePath: { type: String, default: '' }
})

/** 拼出完整路径 —— 子菜单的 path 在数据库里存的是绝对路径,这里做个兼容 */
function fullPath(path) {
  if (!path) return props.basePath
  if (path.startsWith('/')) return path
  return `${props.basePath}/${path}`.replace(/\/+/g, '/')
}

/** 过滤掉标记为隐藏的菜单 */
const visibleItems = computed(() =>
  props.items.filter((item) => !item.meta?.hidden)
)
</script>

<template>
  <template v-for="item in visibleItems" :key="item.path">
    <!-- 有子菜单:渲染成可展开的分组 -->
    <el-sub-menu
      v-if="item.children && item.children.length > 0"
      :index="fullPath(item.path)"
    >
      <template #title>
        <el-icon v-if="item.meta?.icon">
          <component :is="item.meta.icon" />
        </el-icon>
        <span>{{ item.meta?.title }}</span>
      </template>
      <SidebarItem :items="item.children" :base-path="fullPath(item.path)" />
    </el-sub-menu>

    <!-- 叶子菜单:渲染成可点击的链接 -->
    <el-menu-item v-else :index="fullPath(item.path)">
      <el-icon v-if="item.meta?.icon">
        <component :is="item.meta.icon" />
      </el-icon>
      <template #title>
        <span>{{ item.meta?.title }}</span>
      </template>
    </el-menu-item>
  </template>
</template>
