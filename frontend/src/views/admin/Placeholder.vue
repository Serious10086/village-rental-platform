<script setup>
/**
 * 未开发页面的占位。
 *
 * 为什么需要它:批次 1 只实现了认证与权限,但菜单树里已经有资源、订单、
 * 统计等条目。如果没有兜底组件,点这些菜单会因为组件不存在而白屏 ——
 * 那样就没法用"点开菜单看看"来验证权限是否生效。
 *
 * 有了占位页,权限验证可以和功能开发解耦:菜单现在就能点、能看、能对比,
 * 页面内容等对应批次再填。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

const title = computed(() => route.meta?.title || '功能')
const perms = computed(() => route.meta?.perms || '—')
const path = computed(() => route.path)
</script>

<template>
  <div class="placeholder">
    <div class="card">
      <div class="icon-wrap">
        <el-icon :size="34"><Tools /></el-icon>
      </div>

      <h2 class="title">{{ title }}</h2>
      <p class="desc">
        这个页面已经通过权限校验、成功进入了,但功能还未开发。
      </p>

      <el-descriptions :column="1" border size="small" class="meta">
        <el-descriptions-item label="路由路径">
          <code>{{ path }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="所需权限">
          <code>{{ perms }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag type="warning" size="small">待开发</el-tag>
        </el-descriptions-item>
      </el-descriptions>

      <p class="hint">
        能进到这个页面,说明<b>菜单级权限</b>已经生效 ——
        当前账号的角色被授予了该菜单。页面内容会在对应批次补上。
      </p>
    </div>
  </div>
</template>

<style scoped>
.placeholder {
  display: flex;
  justify-content: center;
  padding-top: var(--space-2xl);
}

.card {
  width: 100%;
  max-width: 520px;
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--space-xl);
  text-align: center;
}

.icon-wrap {
  width: 64px;
  height: 64px;
  margin: 0 auto var(--space-md);
  border-radius: var(--radius-full);
  background: var(--color-primary-light);
  color: var(--color-primary);
  display: flex;
  align-items: center;
  justify-content: center;
}

.title {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
}

.desc {
  margin-top: var(--space-sm);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.meta {
  margin-top: var(--space-lg);
  text-align: left;
}

.meta code {
  font-family: var(--font-family-number);
  font-size: var(--font-size-xs);
  color: var(--color-primary);
  background: var(--color-primary-light);
  padding: 2px 6px;
  border-radius: var(--radius-sm);
}

.hint {
  margin-top: var(--space-lg);
  padding-top: var(--space-md);
  border-top: 1px dashed var(--color-border);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: 1.7;
  text-align: left;
}
</style>
