<script setup>
/**
 * 我的纠纷 —— 我是申请人和我是被申请人,合在一页。
 *
 * 和「我的订单」同样用 Tab 分买家/卖家一个道理:两个列表字段、卡片样式
 * 完全一样,只是"我在哪一侧"不同。拆成两页就是把同一套渲染抄两遍。
 *
 * 【撤销按钮的依据来自后端,不是前端算的】
 * 能不能撤销要同时满足"我是申请人"和"管理员还没处理"这两个条件,
 * 由后端在 DisputeVO.canCancel 里算好。前端自己 if 一遍的话,
 * 后端哪天加了"处理中不可撤销"的规则,界面上就会出现一个点了报错的按钮。
 *
 * 【纠纷不动钱、也不改信用分,这一点必须在界面上说清】
 * 调解结果要退款,管理员走「强制取消订单」;要扣分,走「信用分调整」。
 * 用户很容易以为"提交了纠纷钱就冻结了",不说明白他会一直等一个不会来的结果。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMyDisputes, cancelDispute, getDisputeDetail } from '@/api/dispute'
import { useUserStore } from '@/stores/user'
import { DISPUTE_TYPE, DISPUTE_STATUS } from '@/utils/interactDict'
import { formatDateTime, formatDateOnly, formatMoney } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const myId = computed(() => userStore.userInfo?.userId)

const activeTab = ref('applied')
const list = ref([])
const total = ref(0)
const loading = ref(false)
const query = ref({ pageNum: 1, pageSize: 10, status: null })

const statusOptions = Object.entries(DISPUTE_STATUS).map(([value, item]) => ({
  value: Number(value),
  label: item.label
}))

/**
 * 按 Tab 决定"我在哪一侧"。
 *
 * 【为什么不能用后端那个 relatedUserId 来分 Tab】
 * /dispute/mine 服务端会强制 `relatedUserId = 当前登录用户`,并在 SQL 里
 * 展开成 `applicant_id = 我 OR respondent_id = 我`。也就是说,
 * 不额外加条件的话两个 Tab 拿到的是**同一份数据** —— 一个只有标题不同、
 * 内容一模一样的 Tab,比没有 Tab 更糟。
 *
 * 所以这里再按 applicantId / respondentId 收一次窄。这两个条件和服务端
 * 那个 OR 是 AND 关系(见 BizDisputeMapper.xml),收到窄的同时仍然是
 * "只看我自己的",不会因为传了个别人的 id 就读到别人的纠纷。
 */
async function load() {
  loading.value = true
  try {
    const params = {
      pageNum: query.value.pageNum,
      pageSize: query.value.pageSize,
      status: query.value.status ?? undefined
    }
    if (activeTab.value === 'applied') {
      params.applicantId = myId.value
    } else {
      params.respondentId = myId.value
    }
    const res = await getMyDisputes(params)
    list.value = res.rows || []
    total.value = res.total || 0
  } catch (e) {
    list.value = []
  } finally {
    loading.value = false
  }
}

watch(activeTab, () => {
  query.value.pageNum = 1
  load()
})

watch(() => query.value.status, () => {
  query.value.pageNum = 1
  load()
})

onMounted(() => {
  // 订单详情页可以带 ?tab=received 直接落到"别人对我申请的"
  const tab = route.query.tab
  if (tab === 'applied' || tab === 'received') {
    activeTab.value = tab
  }
  load()
})

/**
 * 这条纠纷里"对方"是谁。
 *
 * 我是申请人 → 对方是被申请人;反之亦然。
 * 用 myId 判断而不是用 Tab 判断:同一个 Tab 下两种情况都可能出现
 * (我作为申请人,也可能收到对方反向提交的纠纷)。
 */
function opponentOf(row) {
  return row.applicantId === myId.value
    ? { name: row.respondentName, role: '被申请人', phone: row.respondentPhone }
    : { name: row.applicantName, role: '申请人', phone: row.applicantPhone }
}

function isApplicant(row) {
  return row.applicantId === myId.value
}

// ── 详情抽屉 ──────────────────────────────────────────────────
const drawerVisible = ref(false)
const detail = ref(null)
const detailLoading = ref(false)

async function openDetail(row) {
  drawerVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getDisputeDetail(row.id)
  } catch (e) {
    drawerVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

/**
 * 撤销纠纷。
 *
 * 用 confirm 而不是 prompt:后端的 cancel 接口不接收撤销理由
 * (`PUT /dispute/{id}/cancel`,无请求体)。摆一个填了也传不上去的输入框,
 * 用户会以为自己写的理由被记录下来了 —— 一个假的输入框比没有输入框更坏。
 * 真需要留痕的话,该做的是给后端加字段,而不是在前端假装有。
 */
async function handleCancel(row) {
  try {
    await ElMessageBox.confirm(
      '撤销后订单会恢复到申请纠纷之前的状态,对方会收到通知。' +
      '如果管理员已经开始调解,撤销会被拒绝 —— 那时请直接和管理员沟通。',
      '撤销纠纷申请',
      { confirmButtonText: '确认撤销', cancelButtonText: '再想想', type: 'warning' }
    )
  } catch {
    return
  }

  try {
    await cancelDispute(row.id)
    ElMessage.success('已撤销,订单已恢复')
    load()
    if (drawerVisible.value) {
      openDetail(row)
    }
  } catch (e) {
    // 业务错误已由拦截器提示(例如"管理员已开始处理,无法撤销")
  }
}

function goOrder(orderId) {
  router.push(`/order/${orderId}`)
}
</script>

<template>
  <div class="page-container my-dispute">
    <header class="page-head">
      <div>
        <h1>我的纠纷</h1>
        <p class="text-secondary">
          纠纷由管理员调解,处理期间订单会挂起。调解结果涉及退款或扣分的,
          由管理员另行走「取消订单」和「信用分调整」——
          <b>提交纠纷本身不会冻结资金</b>。
        </p>
      </div>
    </header>

    <el-tabs v-model="activeTab" class="dispute-tabs">
      <el-tab-pane label="我申请的" name="applied" />
      <el-tab-pane label="别人对我申请的" name="received" />
    </el-tabs>

    <div class="filter-bar">
      <el-select v-model="query.status" placeholder="全部状态" clearable class="status-select">
        <el-option
          v-for="opt in statusOptions"
          :key="opt.value"
          :label="opt.label"
          :value="opt.value"
        />
      </el-select>
      <span class="text-secondary text-sm">共 {{ total }} 条</span>
    </div>

    <div v-loading="loading" class="dispute-list">
      <el-empty
        v-if="!loading && list.length === 0"
        :description="activeTab === 'applied'
          ? '你还没有申请过纠纷'
          : '还没有人对你申请纠纷'"
      />

      <article v-for="row in list" :key="row.id" class="dispute-card card">
        <header class="card-head">
          <div class="head-left">
            <span class="dispute-no" @click="openDetail(row)">{{ row.disputeNo }}</span>
            <el-tag :type="DISPUTE_TYPE[row.disputeType]?.type" size="small" effect="plain">
              {{ row.disputeTypeName || DISPUTE_TYPE[row.disputeType]?.label }}
            </el-tag>
            <el-tag
              v-if="!isApplicant(row)"
              size="small"
              type="info"
              effect="plain"
            >
              我是被申请人
            </el-tag>
          </div>
          <el-tag :type="DISPUTE_STATUS[row.status]?.type" effect="dark" size="small">
            {{ row.statusName || DISPUTE_STATUS[row.status]?.label }}
          </el-tag>
        </header>

        <div class="card-body" @click="openDetail(row)">
          <div class="body-main">
            <h3 class="res-name">{{ row.resourceName || '关联资源已删除' }}</h3>

            <p class="text-secondary text-sm">
              订单 <span class="num">{{ row.orderNo }}</span>
              · 原状态 {{ row.orderStatusBeforeName || '—' }}
              · {{ row.orderTypeName }}
            </p>

            <p v-if="row.rentStartDate" class="text-secondary text-sm">
              租期 {{ formatDateOnly(row.rentStartDate) }} 至 {{ formatDateOnly(row.rentEndDate) }}
            </p>

            <p class="text-secondary text-sm">
              {{ opponentOf(row).role }}:{{ opponentOf(row).name }}
              <span v-if="opponentOf(row).phone" class="num">{{ opponentOf(row).phone }}</span>
              · {{ formatDateTime(row.createTime) }}
            </p>

            <p class="desc text-sm">{{ row.description }}</p>
          </div>

          <div class="body-amount">
            <template v-if="row.applyAmount != null">
              <div class="amount-num">¥{{ formatMoney(row.applyAmount) }}</div>
              <div class="text-xs text-secondary">诉求金额</div>
            </template>
            <div v-else class="text-xs text-secondary">未主张金额</div>
            <div class="text-xs text-secondary amount-order">订单 ¥{{ formatMoney(row.payAmount) }}</div>
          </div>
        </div>

        <footer class="card-foot">
          <el-button size="small" plain @click="goOrder(row.orderId)">查看订单</el-button>
          <el-button size="small" type="primary" plain @click="openDetail(row)">查看详情</el-button>
          <el-button
            v-if="row.canCancel"
            size="small"
            type="danger"
            plain
            @click="handleCancel(row)"
          >
            撤销申请
          </el-button>
        </footer>
      </article>
    </div>

    <el-pagination
      v-if="total > query.pageSize"
      v-model:current-page="query.pageNum"
      :page-size="query.pageSize"
      :total="total"
      layout="prev, pager, next"
      class="pager"
      @current-change="load"
    />

    <!-- ── 详情 ────────────────────────────────────────────── -->
    <el-drawer v-model="drawerVisible" title="纠纷详情" size="560px">
      <div v-if="detailLoading" v-loading="true" class="drawer-loading" />

      <template v-else-if="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="纠纷编号">
            <span class="num">{{ detail.disputeNo }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="类型">
            <el-tag :type="DISPUTE_TYPE[detail.disputeType]?.type" size="small" effect="plain">
              {{ detail.disputeTypeName || DISPUTE_TYPE[detail.disputeType]?.label }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="处理状态">
            <el-tag :type="DISPUTE_STATUS[detail.status]?.type" size="small" effect="dark">
              {{ detail.statusName || DISPUTE_STATUS[detail.status]?.label }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="申请人">
            {{ detail.applicantName }} <span class="num">{{ detail.applicantPhone }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="被申请人">
            {{ detail.respondentName }} <span class="num">{{ detail.respondentPhone }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="申请时间">
            {{ formatDateTime(detail.createTime) }}
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="section-title">争议订单</h4>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="订单号">
            <span class="num link" @click="goOrder(detail.orderId)">{{ detail.orderNo }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="订单金额">¥{{ formatMoney(detail.payAmount) }}</el-descriptions-item>
          <el-descriptions-item label="资源">{{ detail.resourceName }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ detail.orderTypeName }}</el-descriptions-item>
          <el-descriptions-item label="纠纷前状态">
            {{ detail.orderStatusBeforeName }}
          </el-descriptions-item>
          <el-descriptions-item label="当前状态">{{ detail.orderStatusName }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.rentStartDate" label="租期" :span="2">
            {{ formatDateOnly(detail.rentStartDate) }} 至 {{ formatDateOnly(detail.rentEndDate) }}
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="section-title">纠纷陈述</h4>
        <div class="statement">
          <p>{{ detail.description }}</p>
          <div v-if="detail.evidenceImages?.length" class="evidence">
            <el-image
              v-for="(url, i) in detail.evidenceImages"
              :key="i"
              :src="url"
              :preview-src-list="detail.evidenceImages"
              :initial-index="i"
              fit="cover"
              class="evidence-img"
              preview-teleported
            >
              <template #error>
                <div class="img-error">图片失效</div>
              </template>
            </el-image>
          </div>
        </div>

        <h4 class="section-title">处理结论</h4>
        <el-alert
          v-if="detail.status === 0 || detail.status === 1"
          type="warning"
          :closable="false"
          show-icon
          title="管理员尚未出结论"
        >
          <template #default>
            调解结束后,订单会恢复到「{{ detail.orderStatusBeforeName }}」。
            需要退款或扣分的,由管理员另行走对应流程,不会在这里直接改资金和信用分。
          </template>
        </el-alert>

        <el-descriptions v-else :column="1" border size="small">
          <el-descriptions-item label="处理人">{{ detail.handlerName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="处理时间">
            {{ formatDateTime(detail.handleTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="结论">{{ detail.handleResult || '—' }}</el-descriptions-item>
        </el-descriptions>

        <div class="drawer-foot">
          <el-button @click="goOrder(detail.orderId)">查看订单</el-button>
          <el-button
            v-if="detail.canCancel"
            type="danger"
            plain
            @click="handleCancel(detail)"
          >
            撤销申请
          </el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.my-dispute {
  padding-top: var(--space-xl);
  padding-bottom: var(--space-2xl);
}

.page-head {
  margin-bottom: var(--space-md);
}

.page-head h1 {
  font-size: var(--font-size-xl);
  margin-bottom: var(--space-xs);
}

.page-head p {
  max-width: 720px;
  line-height: 1.7;
}

.dispute-tabs {
  margin-bottom: var(--space-md);
}

.dispute-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  margin-bottom: var(--space-md);
}

.status-select {
  width: 160px;
}

.dispute-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
  min-height: 200px;
}

.dispute-card {
  padding: 0;
  overflow: hidden;
  transition: box-shadow var(--duration-fast);
}

.dispute-card:hover {
  box-shadow: var(--shadow-md);
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-sm) var(--space-lg);
  background: var(--color-info-light);
  border-bottom: 1px solid var(--color-border-light);
}

.head-left {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.dispute-no {
  font-family: var(--font-family-number);
  font-size: var(--font-size-sm);
  color: var(--color-text-regular);
  cursor: pointer;
}

.dispute-no:hover {
  color: var(--color-primary);
}

.card-body {
  display: flex;
  align-items: flex-start;
  gap: var(--space-md);
  padding: var(--space-md) var(--space-lg);
  cursor: pointer;
}

.body-main {
  flex: 1;
  min-width: 0;
}

.res-name {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  margin-bottom: 2px;
}

.body-main p {
  margin: 2px 0;
}

.desc {
  margin-top: var(--space-xs) !important;
  padding: var(--space-xs) var(--space-sm);
  color: var(--color-text-regular);
  background: var(--color-bg-page);
  border-radius: var(--radius-sm);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.body-amount {
  text-align: right;
  flex-shrink: 0;
}

.amount-num {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-warning);
  font-family: var(--font-family-number);
}

.amount-order {
  margin-top: var(--space-xs);
}

.card-foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-sm);
  padding: var(--space-sm) var(--space-lg);
  border-top: 1px solid var(--color-border-light);
}

.pager {
  margin-top: var(--space-lg);
  justify-content: center;
}

/* ── 抽屉 ─────────────────────────────────────────────────── */
.drawer-loading {
  height: 200px;
}

.section-title {
  margin: var(--space-lg) 0 var(--space-sm);
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  padding-left: var(--space-sm);
  border-left: 3px solid var(--color-primary);
}

.num {
  font-family: var(--font-family-number);
}

.link {
  color: var(--color-primary);
  cursor: pointer;
}

.statement p {
  margin-bottom: var(--space-sm);
  line-height: 1.8;
  color: var(--color-text-regular);
  white-space: pre-wrap;
}

.evidence {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.evidence-img {
  width: 96px;
  height: 96px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
}

.img-error {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  font-size: var(--font-size-xs);
  color: var(--color-text-placeholder);
  background: var(--color-bg-page);
}

.drawer-foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-sm);
  margin-top: var(--space-xl);
  padding-top: var(--space-md);
  border-top: 1px solid var(--color-border-light);
}
</style>
