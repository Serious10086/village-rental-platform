<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="not-found">
    <div class="inner">
      <div class="code num">404</div>
      <h1 class="title">页面走丢了</h1>
      <p class="desc">你访问的地址不存在,或者已经被移走了。</p>
      <div class="actions">
        <el-button type="primary" @click="router.push('/')">返回首页</el-button>
        <el-button @click="router.back()">回到上一页</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.not-found {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-xl);
}

.inner {
  text-align: center;
  max-width: 420px;
}

.code {
  font-size: 96px;
  font-weight: var(--font-weight-bold);
  line-height: 1;
  letter-spacing: -0.04em;
  /* 用主色渐变做数字,比纯色更有质感,也不至于花哨 */
  background: linear-gradient(135deg, var(--color-primary) 0%, #60a5fa 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  margin-top: var(--space-lg);
}

.desc {
  color: var(--color-text-secondary);
  margin-top: var(--space-sm);
}

.actions {
  margin-top: var(--space-xl);
  display: flex;
  gap: var(--space-sm);
  justify-content: center;
}
</style>
