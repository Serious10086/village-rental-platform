<script setup>
/**
 * 根组件。
 *
 * 只负责挂载路由出口和全局的登录态初始化。
 * 具体的页面布局(前台导航栏 / 后台侧边栏)在各自的布局组件里做,
 * 不在这里堆条件判断 —— 那样根组件会变成一个巨大的 if-else。
 */
</script>

<template>
  <router-view v-slot="{ Component }">
    <transition name="fade" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>
</template>
