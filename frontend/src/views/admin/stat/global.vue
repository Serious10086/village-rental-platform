<script setup>
/**
 * 后台 —— 全局统计(数据统计公示)。
 *
 * 【这一页和"村级统计"的分工】
 * 村级统计看的是**一个村的纵向情况**(资源、订单、利用率),
 * 这一页看的是**各村之间的横向对比** —— "哪个村的资源盘活得好"。
 * 所以主角是那张分组柱状图和下面那张按利用率排名的表,
 * 而不是单个村的明细。
 *
 * 【"公示"这两个字是需求原文】
 * 需求里写的是"数据统计公示页" —— 它的用途是**公开出来让人对照**,
 * 所以表里既有绝对数(资源 12、订单 5)也有比率(利用率 41.67%),
 * 而且按村排开。只给一个平台总数的话,谁也看不出差距在哪。
 *
 * 仅超管可见:菜单授权里 {@code stat:global} 只授给了超管,
 * 接口上还有一道 @RequiresPerm,服务里第三道再判一次超管。
 */
import { ref, computed, onMounted } from 'vue'
import { getGlobalStat } from '@/api/stat'
import EChart from '@/components/EChart.vue'
import { pieOption, villageCompareOption } from '@/utils/chart'

const loading = ref(false)
const stat = ref({})

/** 各村按利用率倒序 —— 公示页要的是"谁做得好",不是"谁 id 小" */
const rankedVillages = computed(() =>
  [...(stat.value.villages || [])].sort(
    (a, b) => Number(b.usageRate || 0) - Number(a.usageRate || 0)
  )
)

async function fetchStat() {
  loading.value = true
  try {
    stat.value = (await getGlobalStat()) || {}
  } catch (e) {
    stat.value = {}
  } finally {
    loading.value = false
  }
}

function rateTagType(rate) {
  const n = Number(rate || 0)
  if (n >= 50) return 'success'
  if (n >= 20) return 'warning'
  return 'info'
}

onMounted(fetchStat)
</script>

<template>
  <div class="admin-global-stat">
    <el-alert type="info" :closable="false" show-icon class="scope-alert">
      <template #title>
        全平台口径,不区分村组。数据来自视图 <b>v_village_resource_stat</b>。
        <b>平台利用率是用汇总后的两个数重新算的,不是各村利用率的平均</b> ——
        一个只有 1 个资源、恰好成过 1 单的村会有 100% 的利用率,
        把它平均进去会显著拉高整体,而它代表不了整个平台。
      </template>
    </el-alert>

    <section v-loading="loading" class="stat-grid">
      <article class="stat-card">
        <div class="stat-label">资源总数</div>
        <div class="stat-value num">{{ stat.resourceTotal || 0 }}</div>
        <div class="stat-hint">在架 {{ stat.onlineCount || 0 }}</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">注册用户</div>
        <div class="stat-value num">{{ stat.userTotal || 0 }}</div>
        <div class="stat-hint">全平台</div>
      </article>
      <article class="stat-card is-accent">
        <div class="stat-label">平台租售利用率</div>
        <div class="stat-value num">{{ stat.usageRate ?? 0 }}%</div>
        <div class="stat-hint">(交易中 + 已完成)÷ 资源总数</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">订单总数</div>
        <div class="stat-value num">{{ stat.orderTotal || 0 }}</div>
        <div class="stat-hint">已完成 {{ stat.orderFinished || 0 }}</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">高风险资源</div>
        <div class="stat-value num">{{ stat.highRiskCount || 0 }}</div>
        <div class="stat-hint">需持证 / 培训</div>
      </article>
    </section>

    <div class="chart-grid">
      <div class="card chart-card">
        <h3 class="card-title">各村资源与订单对比</h3>
        <p class="card-sub">
          看的是两根柱子的**比例**:资源多订单少 = 没盘活;两者都少 = 本来就没东西可租
        </p>
        <EChart :option="villageCompareOption(stat.villages)" :loading="loading" height="320px" />
      </div>

      <div class="card chart-card">
        <h3 class="card-title">全平台资源分类分布</h3>
        <p class="card-sub">和村级统计那张口径一致(只算在架),两个页面能直接对照</p>
        <EChart :option="pieOption(stat.categoryDist)" :loading="loading" height="320px" />
      </div>
    </div>

    <div class="card table-card">
      <h3 class="card-title">各村明细(按租售利用率排序)</h3>
      <p class="card-sub">公示用 —— 既给绝对数也给比率,只给总数看不出差距在哪</p>
      <el-table :data="rankedVillages" v-loading="loading" stripe>
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column label="村组" min-width="140">
          <template #default="{ row }">
            <span class="village-name">{{ row.villageName }}</span>
            <span class="text-xs text-secondary"> · {{ row.townName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="资源总数" width="100" align="right">
          <template #default="{ row }"><span class="num">{{ row.resourceTotal }}</span></template>
        </el-table-column>
        <el-table-column label="在架" width="90" align="right">
          <template #default="{ row }"><span class="num">{{ row.onlineCount }}</span></template>
        </el-table-column>
        <el-table-column label="可租 / 在售" width="110" align="right">
          <template #default="{ row }">
            <span class="num text-xs">{{ row.rentCount }} / {{ row.sellCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="用户" width="80" align="right">
          <template #default="{ row }"><span class="num">{{ row.userTotal }}</span></template>
        </el-table-column>
        <el-table-column label="订单" width="100" align="right">
          <template #default="{ row }">
            <span class="num">{{ row.orderTotal }}</span>
            <span class="text-xs text-secondary">({{ row.orderFinished }}完成)</span>
          </template>
        </el-table-column>
        <el-table-column label="高风险" width="90" align="right">
          <template #default="{ row }"><span class="num">{{ row.highRiskCount }}</span></template>
        </el-table-column>
        <el-table-column label="租售利用率" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="rateTagType(row.usageRate)" effect="plain" size="small">
              {{ row.usageRate ?? 0 }}%
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.scope-alert {
  margin-bottom: var(--space-md);
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
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

.chart-grid {
  display: grid;
  grid-template-columns: 1.4fr 1fr;
  gap: var(--space-md);
  margin-bottom: var(--space-md);
}

.chart-card {
  padding: var(--space-md) var(--space-lg);
}

.card-title {
  margin: 0 0 4px;
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.card-sub {
  margin: 0 0 var(--space-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: 1.6;
}

.table-card {
  padding: var(--space-md) var(--space-lg);
}

.village-name {
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

@media (max-width: 1280px) {
  .stat-grid {
    grid-template-columns: repeat(3, 1fr);
  }

  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
