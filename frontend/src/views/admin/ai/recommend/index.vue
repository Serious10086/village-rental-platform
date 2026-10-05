<script setup>
/**
 * 后台 —— 推荐效果(批次 8)。
 *
 * 【这一页最该说清楚的是"分母"】
 * 只报点击数是没有意义的:一天 50 次点击是多还是少,没有分母就答不上来。
 * 所以界面上永远是「曝光 / 点击 / 点击率」三个数一起出现,
 * 而且写明**分母只含登录用户** —— 游客的进出不记录(隐私 + 表结构所限),
 * 把那个率当成"全站点击率"会读错。
 *
 * 【另一个要说清楚的是"这一页做不到什么"】
 * 它分不出"哪个推荐策略贡献的点击"。要按策略拆,就得在点击记录里
 * 存下"这条推荐是哪个策略推出来的",而 ai_user_behavior 没有那一列。
 * 这件事写在页面上,不是藏在代码注释里 —— 免得有人对着这个页面
 * 反复琢磨为什么找不到那个维度。
 */
import { ref, reactive, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { getRecommendStat } from '@/api/recommend'

const loading = ref(false)
const stat = ref({})

const query = reactive({ days: 7 })

const dayOptions = [
  { value: 1, label: '今天' },
  { value: 7, label: '最近 7 天' },
  { value: 30, label: '最近 30 天' },
  { value: 90, label: '最近 90 天' }
]

/** 每日明细里最长的那根柱子,用来按比例画宽度 */
function barWidth(row) {
  const max = Math.max(1, ...(stat.value.daily || []).map((d) => d.impressions || 0))
  return `${Math.max(4, ((row.impressions || 0) / max) * 100)}%`
}

async function fetchStat() {
  loading.value = true
  try {
    stat.value = (await getRecommendStat({ days: query.days })) || {}
  } catch (e) {
    stat.value = {}
  } finally {
    loading.value = false
  }
}

onMounted(fetchStat)
</script>

<template>
  <div class="admin-recommend">
    <el-alert type="info" :closable="false" show-icon class="scope-alert">
      <template #title>
        <b>点击率 = 点击 ÷ 曝光</b>,其中曝光是"推荐位被展示了几次"
        (一次推荐请求记一行)。<b>分母只含登录用户</b> —— 游客的访问不计入,
        所以这是「登录用户的推荐位点击率」,不是全站的。
        另外这一页<b>分不出哪个推荐策略贡献的点击</b>:那需要在点击记录里
        存下策略,而行为表没有那一列。
      </template>
    </el-alert>

    <div class="card filter-bar">
      <el-select v-model="query.days" style="width: 150px" @change="fetchStat">
        <el-option v-for="d in dayOptions" :key="d.value" :label="d.label" :value="d.value" />
      </el-select>
      <el-button :icon="Refresh" @click="fetchStat">刷新</el-button>
    </div>

    <!-- ── 三个数一起出现,缺一个都读不懂 ────────────────────── -->
    <section v-loading="loading" class="stat-grid">
      <article class="stat-card">
        <div class="stat-label">推荐位曝光</div>
        <div class="stat-value num">{{ stat.impressions || 0 }}</div>
        <div class="stat-hint">推荐位被展示的次数(一次请求算一次)</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">点击</div>
        <div class="stat-value num">{{ stat.clicks || 0 }}</div>
        <div class="stat-hint">从推荐位点进资源详情的次数</div>
      </article>
      <article class="stat-card is-accent">
        <div class="stat-label">点击率</div>
        <div class="stat-value num">{{ stat.ctr ?? 0 }}%</div>
        <div class="stat-hint">点击 ÷ 曝光</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">推荐位占全部浏览</div>
        <div class="stat-value num">{{ stat.clickShare ?? 0 }}%</div>
        <div class="stat-hint">同期全部浏览 {{ stat.totalViews || 0 }} 次</div>
      </article>
    </section>

    <div class="card trend-card">
      <div class="card-head">
        <h3 class="card-title">行为采集量</h3>
        <span class="card-sub">
          推荐的数据来源。<b>某一种长期为 0 说明那条链路的采集断了</b> ——
          推荐会因此悄悄变笨,而界面上完全看不出来,所以这一块要盯着
        </span>
      </div>
      <div v-if="stat.behaviors?.length" class="behavior-row">
        <div v-for="b in stat.behaviors" :key="b.type" class="behavior-item">
          <div class="behavior-name">{{ b.name }}</div>
          <div class="behavior-count num">{{ b.count }}</div>
        </div>
      </div>
      <el-empty v-else :image-size="60" description="这段时间还没有采集到任何行为" />
    </div>

    <div class="card trend-card">
      <div class="card-head">
        <h3 class="card-title">按天趋势</h3>
        <span class="card-sub">
          推荐这种指标看趋势比看单点有用:某天开始明显下降,说明推的内容不对了
        </span>
      </div>

      <div v-if="stat.daily?.length" class="trend-list">
        <div v-for="d in stat.daily" :key="d.date" class="trend-row">
          <span class="trend-date num">{{ d.date }}</span>
          <div class="trend-track">
            <div class="trend-bar" :style="{ width: barWidth(d) }" />
          </div>
          <span class="trend-num num">
            {{ d.clicks }} / {{ d.impressions }}
          </span>
          <span class="trend-ctr num">{{ d.ctr ?? 0 }}%</span>
        </div>
      </div>
      <el-empty v-else :image-size="60" description="这段时间还没有推荐位的曝光记录" />
    </div>
  </div>
</template>

<style scoped>
.scope-alert {
  margin-bottom: var(--space-md);
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-md) var(--space-lg);
  margin-bottom: var(--space-md);
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-md);
  margin-bottom: var(--space-md);
}

.stat-card {
  padding: var(--space-md) var(--space-lg);
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
}

.stat-card.is-accent {
  background: var(--color-primary-light);
  border-color: var(--color-primary-border);
}

.stat-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.stat-value {
  margin: 6px 0 4px;
  font-size: 26px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  line-height: 1.1;
}

.stat-card.is-accent .stat-value {
  color: var(--color-primary);
}

.stat-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: 1.5;
}

.trend-card {
  padding: var(--space-md) var(--space-lg);
  margin-bottom: var(--space-md);
}

/* ── 行为采集量 ────────────────────────────────────────────── */
.behavior-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xl);
}

.behavior-item {
  min-width: 90px;
}

.behavior-name {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.behavior-count {
  margin-top: 4px;
  font-size: 22px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  line-height: 1.1;
}

.card-head {
  margin-bottom: var(--space-md);
}

.card-title {
  margin: 0 0 4px;
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.card-sub {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.trend-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.trend-row {
  display: grid;
  grid-template-columns: 100px 1fr 90px 60px;
  align-items: center;
  gap: var(--space-sm);
}

.trend-date {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.trend-track {
  height: 10px;
  background: var(--color-info-light);
  border-radius: var(--radius-full);
  overflow: hidden;
}

.trend-bar {
  height: 100%;
  background: linear-gradient(90deg, var(--color-primary), #3b82f6);
  border-radius: var(--radius-full);
  transition: width var(--duration-normal) var(--ease-out);
}

.trend-num {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  text-align: right;
}

.trend-ctr {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  text-align: right;
}

@media (max-width: 1280px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
