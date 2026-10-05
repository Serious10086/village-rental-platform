<script setup>
/**
 * 后台工作台。
 *
 * 【这一页现在有两块,顺序是刻意的】
 * 上面是<b>业务看板</b>(批次 9 补上):本村/全平台的资源、订单、利用率,
 * 加三张图 —— 管理员每天进来先看的是"村里的资源盘活得怎么样"。
 * 下面是<b>权限概览</b>(批次 2 就有的):我的菜单数、权限码、数据范围 ——
 * 那是答辩演示"基于角色的权限管控"最直观的一块屏幕,所以留着;
 * 但它不是每天要看的东西,因此排到业务数字后面。
 *
 * 两块的范围是同一个:村管看本村,超管看全平台,都由后端按登录态决定。
 */
import { ref, computed, onMounted } from 'vue'
import { useUserStore } from '@/stores/user'
import { usePermissionStore } from '@/stores/permission'
import { getDashboardStat } from '@/api/stat'
import EChart from '@/components/EChart.vue'
import { pieOption, barOption, lineOption } from '@/utils/chart'

const userStore = useUserStore()
const permissionStore = usePermissionStore()

/** 业务统计。加载失败时保持空对象,下半部分的权限概览照常显示 */
const statLoading = ref(false)
const stat = ref({})

async function loadStat() {
  statLoading.value = true
  try {
    stat.value = (await getDashboardStat()) || {}
  } catch (e) {
    stat.value = {}
  } finally {
    statLoading.value = false
  }
}

onMounted(loadStat)

/** 统计菜单树上的节点数 */
function countNodes(routes, type) {
  let count = 0
  for (const r of routes) {
    if (type === 'top') {
      count += 1
    } else if (!r.children || r.children.length === 0) {
      count += 1 // 叶子菜单
    } else {
      count += countNodes(r.children, type)
    }
  }
  return count
}

const topCount = computed(() => permissionStore.dynamicRoutes.length)
const leafCount = computed(() => countNodes(permissionStore.dynamicRoutes, 'leaf'))
const permCount = computed(() => userStore.perms.size)

/** 权限码按模块前缀分组展示 */
const permsByModule = computed(() => {
  const groups = {}
  for (const p of userStore.perms) {
    const module = p.split(':')[0]
    if (!groups[module]) groups[module] = []
    groups[module].push(p)
  }
  return Object.entries(groups).sort((a, b) => a[0].localeCompare(b[0]))
})

const scopeLabel = computed(() => {
  const map = {
    1: '全部数据(可管理所有村组)',
    2: '本镇数据(可管理本镇下所有村)',
    3: '本村数据(仅可管理所属村组)',
    4: '仅本人数据(只能操作自己的数据)'
  }
  return map[userStore.userInfo?.dataScope] || '未分配'
})

const roleLabel = computed(() => {
  const map = {
    SUPER_ADMIN: '超级管理员',
    VILLAGE_ADMIN: '村级管理员',
    VILLAGER: '资源发布者',
    BUYER: '资源使用者'
  }
  return [...userStore.roles].map((r) => map[r] || r).join(' / ') || '无角色'
})
</script>

<template>
  <div class="dashboard">
    <!-- ── 欢迎区 ──────────────────────────────────────────── -->
    <section class="welcome">
      <div class="welcome-text">
        <h2>你好,{{ userStore.userInfo?.realName || userStore.userInfo?.phone }}</h2>
        <p class="sub">
          当前身份:<b>{{ roleLabel }}</b>
          <span v-if="userStore.userInfo?.villageName">
            · 所属村组:<b>{{ userStore.userInfo.villageName }}</b>
          </span>
        </p>
      </div>
      <div class="welcome-meta">
        <div class="meta-item">
          <span class="meta-label">信用分</span>
          <span class="meta-value num">{{ userStore.userInfo?.creditScore ?? '—' }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">实名状态</span>
          <el-tag :type="userStore.isRealnameVerified ? 'success' : 'warning'" size="small" effect="light">
            {{ { 0: '未提交', 1: '待审核', 2: '已认证', 3: '已驳回' }[userStore.userInfo?.realnameStatus] || '未知' }}
          </el-tag>
        </div>
      </div>
    </section>

    <!-- ══ 业务看板(批次 9)══════════════════════════════════ -->
    <!--
      范围由后端按登录态决定:村管是本村,超管是全平台。
      页面上不写"本村"两个字,而是显示后端返回的口径 ——
      超管的账号挂在镇节点上,他没有"本村"这个概念。
    -->
    <section v-loading="statLoading" class="biz-grid">
      <article class="stat-card is-accent">
        <div class="stat-label">租售利用率</div>
        <div class="stat-value num">{{ stat.usageRate ?? 0 }}%</div>
        <div class="stat-hint">(交易中 + 已完成)÷ 资源总数</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">在架资源</div>
        <div class="stat-value num">{{ stat.onlineCount || 0 }}</div>
        <div class="stat-hint">共 {{ stat.resourceTotal || 0 }} 条(含未上架)</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">订单总数</div>
        <div class="stat-value num">{{ stat.orderTotal || 0 }}</div>
        <div class="stat-hint">已完成 {{ stat.orderFinished || 0 }} / 使用中 {{ stat.orderUsing || 0 }}</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">注册用户</div>
        <div class="stat-value num">{{ stat.userTotal || 0 }}</div>
        <div class="stat-hint">统计范围内</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">高风险资源</div>
        <div class="stat-value num">{{ stat.highRiskCount || 0 }}</div>
        <div class="stat-hint">需持证 / 培训</div>
      </article>
    </section>

    <div class="chart-grid">
      <div class="card chart-card">
        <h3 class="chart-title">在架资源的分类分布</h3>
        <EChart :option="pieOption(stat.categoryDist)" :loading="statLoading" height="280px" />
      </div>
      <div class="card chart-card">
        <h3 class="chart-title">订单状态分布</h3>
        <p class="chart-sub">停在"待付款""待交付"的订单多,说明流程卡住了,不是没人想租</p>
        <EChart :option="barOption(stat.orderStatusDist)" :loading="statLoading" height="260px" />
      </div>
    </div>

    <div class="card chart-card trend-card">
      <h3 class="chart-title">近 30 天订单趋势</h3>
      <EChart :option="lineOption(stat.orderTrend)" :loading="statLoading" height="220px" />
    </div>

    <!-- ── 权限概览(答辩演示用,不是每天要看的东西) ──────── -->
    <h2 class="block-title">权限概览</h2>
    <p class="block-sub">
      同一块屏幕,不同角色看到的数字完全不同 —— 这就是"基于角色的权限管控"
      最直观的样子。完整的数据统计在「数据统计」菜单下。
    </p>

    <section class="stat-grid">
      <article class="stat-card">
        <div class="stat-label">可见一级菜单</div>
        <div class="stat-value num">{{ topCount }}</div>
        <div class="stat-hint">由角色授权的菜单树决定</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">可访问页面</div>
        <div class="stat-value num">{{ leafCount }}</div>
        <div class="stat-hint">不含目录节点</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">持有权限码</div>
        <div class="stat-value num">{{ permCount }}</div>
        <div class="stat-hint">控制按钮级显示与操作</div>
      </article>
      <article class="stat-card is-accent">
        <div class="stat-label">数据范围</div>
        <div class="stat-value-sm">{{ scopeLabel }}</div>
        <div class="stat-hint">决定能查到哪些村的数据</div>
      </article>
    </section>

    <!-- ── 两层说明 ────────────────────────────────────────── -->
    <section class="panels">
      <!-- 菜单树 -->
      <div class="panel">
        <header class="panel-head">
          <h3>我的菜单(菜单级权限)</h3>
          <span class="text-xs text-secondary">这些页面由后端下发,未授权的路由不存在</span>
        </header>
        <el-tree
          :data="permissionStore.dynamicRoutes"
          :props="{ label: (d) => d.meta?.title, children: 'children' }"
          default-expand-all
          class="menu-tree"
        />
      </div>

      <!-- 权限码 -->
      <div class="panel">
        <header class="panel-head">
          <h3>我的权限码(按钮级权限)</h3>
          <span class="text-xs text-secondary">前端控制显隐,后端强制校验</span>
        </header>

        <div v-if="permsByModule.length === 0" class="empty">
          <el-empty :image-size="50" description="没有可操作的按钮权限" />
        </div>

        <div v-else class="perm-groups">
          <div v-for="[module, list] in permsByModule" :key="module" class="perm-group">
            <div class="perm-module">{{ module }}</div>
            <div class="perm-tags">
              <el-tag
                v-for="p in list"
                :key="p"
                size="small"
                effect="plain"
                class="perm-tag"
              >
                {{ p }}
              </el-tag>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── 演示提示 ────────────────────────────────────────── -->
    <section class="tip-card">
      <el-icon :size="18"><InfoFilled /></el-icon>
      <div>
        <b>权限差异怎么演示:</b>
        用「平台管理员」(13800000000)登录,能看到左侧 <b>系统管理</b> 整组菜单;
        换成「战旗村支书」(13800000001)登录,<b>系统管理</b> 整组消失,
        权限码数量也从 46 降到 33 —— 这是菜单级权限在起作用。
        再用村支书去访问用户列表,只能看到战旗村的人,这是数据级权限在起作用。
      </div>
    </section>
  </div>
</template>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}

/* ── 欢迎区 ──────────────────────────────────────────────── */
.welcome {
  background: linear-gradient(120deg, var(--color-primary) 0%, #3b82f6 100%);
  color: #fff;
  border-radius: var(--radius-lg);
  padding: var(--space-lg) var(--space-xl);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-lg);
  flex-wrap: wrap;
}

.welcome-text h2 {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.sub {
  margin-top: var(--space-xs);
  font-size: var(--font-size-sm);
  color: rgba(255, 255, 255, 0.85);
}

.sub b {
  font-weight: var(--font-weight-bold);
}

.welcome-meta {
  display: flex;
  gap: var(--space-xl);
}

.meta-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.meta-label {
  font-size: var(--font-size-xs);
  color: rgba(255, 255, 255, 0.75);
}

.meta-value {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

/* ── 统计卡片 ────────────────────────────────────────────── */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--space-md);
}

.stat-card {
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-md) var(--space-lg);
}

.stat-card.is-accent {
  border-left: 3px solid var(--color-primary);
}

.stat-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.stat-value {
  font-size: 30px;
  font-weight: var(--font-weight-bold);
  line-height: 1.3;
  color: var(--color-primary);
}

.stat-value-sm {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  line-height: 1.5;
  margin-top: 4px;
}

.stat-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-placeholder);
}

/* ── 面板 ────────────────────────────────────────────────── */
.panels {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-md);
}

.panel {
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-lg);
}

.panel-head {
  margin-bottom: var(--space-md);
}

.panel-head h3 {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
}

.menu-tree {
  background: transparent;
}

.menu-tree :deep(.el-tree-node__content) {
  height: 32px;
}

.perm-groups {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.perm-module {
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: var(--space-sm);
}

.perm-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.perm-tag {
  font-family: var(--font-family-number);
  font-size: 11px;
}

.empty {
  padding: var(--space-md) 0;
}

/* ── 提示卡 ──────────────────────────────────────────────── */
.tip-card {
  display: flex;
  gap: var(--space-sm);
  background: var(--color-primary-light);
  border: 1px solid var(--color-primary-border);
  border-radius: var(--radius-md);
  padding: var(--space-md) var(--space-lg);
  font-size: var(--font-size-sm);
  line-height: 1.7;
  color: var(--color-text-regular);
}

.tip-card .el-icon {
  color: var(--color-primary);
  flex-shrink: 0;
  margin-top: 3px;
}

/* ── 业务看板 ────────────────────────────────────────────── */
/* 5 个卡片:利用率排第一格并用主色描出来 —— 它是这一屏的主角,
   其余四个是解释它的(资源多不多、订单有没有、用户在不在、风险高不高) */
.biz-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: var(--space-md);
  margin-bottom: var(--space-md);
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

.chart-title {
  margin: 0 0 var(--space-sm);
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.chart-sub {
  margin: 0 0 var(--space-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: 1.6;
}

.trend-card {
  margin-bottom: var(--space-xl);
}

/* 两个版块之间的分界。这一行同时也在告诉读者"上面是业务、下面是权限" */
.block-title {
  margin: 0 0 6px;
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.block-sub {
  margin: 0 0 var(--space-md);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: 1.7;
}

/* ── 响应式 ──────────────────────────────────────────────── */
@media (max-width: 1280px) {
  .biz-grid {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (max-width: 1000px) {
  .panels {
    grid-template-columns: 1fr;
  }

  .biz-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
