<script setup>
/**
 * 后台 —— 村级统计。
 *
 * 【这一页要回答的问题】
 * "我这个村的闲置资源盘活了没有" —— 有多少能租的东西、被用上了多少、
 * 分布在哪些品类上。所以关键数字里 **租售利用率** 是主角,其余是它的解释:
 * 利用率低,要么是资源太少(分母小)、要么是没人下单(分子小),
 * 下面那几张图就是用来分清是哪一种的。
 *
 * 【范围由登录态决定,不由这一页的查询条件决定】
 * 村级管理员只能看本村,连选择器都不给他显示 —— 显示了却点不动,
 * 比不显示更让人困惑。超管则可以切村:他没有"本村"这个概念
 * (账号挂在镇节点上),必须让他选。
 *
 * 接口层还有一道:`villageId` 参数**只有超管传了才算**,村管传什么都会被忽略。
 * 前端这一层只是"别让用户看见用不了的东西",不是权限边界。
 */
import { ref, computed, onMounted } from 'vue'
import { getVillageStat } from '@/api/stat'
import { getVillageList } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import EChart from '@/components/EChart.vue'
import { pieOption, barOption, lineOption } from '@/utils/chart'

const userStore = useUserStore()

const loading = ref(false)
const stat = ref({})
/** 超管选中的村;村管用不上这个值 */
const selectedVillage = ref(null)
const villages = ref([])

const isSuperAdmin = computed(() => userStore.userInfo?.superAdmin === true)

async function fetchStat() {
  loading.value = true
  try {
    stat.value = (await getVillageStat(selectedVillage.value)) || {}
  } catch (e) {
    stat.value = {}
  } finally {
    loading.value = false
  }
}

/**
 * 超管:拉村组列表,并把选择器对齐到"后端实际统计的那个村"。
 *
 * 【为什么要对齐,而不是前端自己挑一个默认值】
 * 后端在没收到 villageId 时会挑一个资源最多的村(它知道哪个村有内容)。
 * 如果前端也自己挑一个(比如取列表第一个),两边可能不是同一个村 ——
 * 表现是选择器上写着"先锋村",而页面上的数字是战旗村的。
 * 所以前端**先让后端挑**,再把它选的那个同步到选择器上。
 */
async function loadVillages() {
  if (!isSuperAdmin.value) {
    return
  }
  try {
    const all = (await getVillageList()) || []
    // 过滤掉镇节点:它是村组的父级,本身不挂资源,选中它统计里全是 0
    villages.value = all.filter((v) => v.parentId != null)
    // 后端把"它统计的是哪个村"放在 villages 里返回了(就一行)
    const served = stat.value.villages?.[0]?.villageId
    if (served != null) {
      selectedVillage.value = served
    }
  } catch (e) {
    villages.value = []
  }
}

/** 当前正在看哪个村 —— 超管切村之后页面上要能看出自己看的是哪个 */
const currentVillageName = computed(() => {
  if (!isSuperAdmin.value) {
    return userStore.userInfo?.villageName || '本村'
  }
  return stat.value.villages?.[0]?.villageName
    || villages.value.find((v) => v.id === selectedVillage.value)?.villageName
    || '所选村组'
})

function handleVillageChange() {
  fetchStat()
}

onMounted(async () => {
  await fetchStat()
  await loadVillages()
})
</script>

<template>
  <div class="admin-stat">
    <el-alert type="info" :closable="false" show-icon class="scope-alert">
      <template #title>
        统计范围:<b>{{ currentVillageName }}</b>。
        数据来自视图 <b>v_village_resource_stat</b>(村级统计、各村对比、数据公示三处共用同一套口径)。
        <b>租售利用率 =(交易中 + 已完成)÷ 资源总数</b> ——
        它低,要么是资源太少、要么是没人下单,下面几张图用来分清是哪一种。
      </template>
    </el-alert>

    <div class="card filter-bar">
      <template v-if="isSuperAdmin">
        <span class="label">选择村组</span>
        <el-select
          v-model="selectedVillage"
          placeholder="请选择村组"
          style="width: 200px"
          @change="handleVillageChange"
        >
          <el-option
            v-for="v in villages"
            :key="v.id"
            :label="v.villageName"
            :value="v.id"
          />
        </el-select>
      </template>
      <span v-else class="text-secondary text-xs">
        你只能看本村的统计 —— 范围由登录身份决定,不由查询条件决定
      </span>
    </div>

    <!-- ── 关键数字 ──────────────────────────────────────── -->
    <section v-loading="loading" class="stat-grid">
      <article class="stat-card">
        <div class="stat-label">资源总数</div>
        <div class="stat-value num">{{ stat.resourceTotal || 0 }}</div>
        <div class="stat-hint">含未上架的</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">在架资源</div>
        <div class="stat-value num">{{ stat.onlineCount || 0 }}</div>
        <div class="stat-hint">可租 {{ stat.rentCount || 0 }} / 在售 {{ stat.sellCount || 0 }}</div>
      </article>
      <article class="stat-card is-accent">
        <div class="stat-label">租售利用率</div>
        <div class="stat-value num">{{ stat.usageRate ?? 0 }}%</div>
        <div class="stat-hint">(交易中 + 已完成)÷ 资源总数</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">订单总数</div>
        <div class="stat-value num">{{ stat.orderTotal || 0 }}</div>
        <div class="stat-hint">已完成 {{ stat.orderFinished || 0 }} / 使用中 {{ stat.orderUsing || 0 }}</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">用户数</div>
        <div class="stat-value num">{{ stat.userTotal || 0 }}</div>
        <div class="stat-hint">本村注册用户</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">高风险资源</div>
        <div class="stat-value num">{{ stat.highRiskCount || 0 }}</div>
        <div class="stat-hint">需持证或培训那类</div>
      </article>
    </section>

    <!-- ── 图表 ──────────────────────────────────────────── -->
    <div class="chart-grid">
      <div class="card chart-card">
        <h3 class="card-title">在架资源的分类分布</h3>
        <p class="card-sub">只统计在架的 —— 待审核和已下架的东西出现在这儿,这张图就答不了"现在有什么能租"</p>
        <EChart :option="pieOption(stat.categoryDist)" :loading="loading" height="300px" />
      </div>

      <div class="card chart-card">
        <h3 class="card-title">订单状态分布</h3>
        <p class="card-sub">停在"待付款""待交付"的订单多,说明流程卡住了,不是没人想租</p>
        <EChart :option="barOption(stat.orderStatusDist)" :loading="loading" height="300px" />
      </div>
    </div>

    <div class="card chart-card">
      <h3 class="card-title">近 30 天订单趋势</h3>
      <p class="card-sub">只显示有订单的那些天 —— 中间断掉的地方就是那天一单没有</p>
      <EChart :option="lineOption(stat.orderTrend)" :loading="loading" height="260px" />
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

.label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
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
  grid-template-columns: 1fr 1fr;
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

@media (max-width: 1280px) {
  .stat-grid {
    grid-template-columns: repeat(3, 1fr);
  }

  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
