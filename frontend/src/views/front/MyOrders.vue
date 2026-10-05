<script setup>
/**
 * 我的订单 —— 买家视角("我买到的")与卖家视角("我卖出的")合在一页。
 *
 * 为什么合成一个页面用 Tab 切换:两个列表的字段、卡片样式完全一样,
 * 只是过滤条件不同(买家ID / 卖家ID)。拆成两个页面等于把同一套
 * 列表渲染代码抄两遍,改一处漏一处。
 *
 * 【按钮不是前端判断出来的】
 * 每个订单的 actions 由后端按状态机算好返回。前端只做"动作码 → 按钮"的翻译,
 * 不自己写 if (status === 1)。这样后端改了流转规则,前端不用跟着改,
 * 也不会出现"按钮能点但点了报错"。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getMyPurchases, getMySales,
  confirmOrder, rejectOrder, payOrder, deliverOrder,
  receiveOrder, returnOrder, cancelOrder
} from '@/api/order'
import { ORDER_STATUS, ORDER_TYPE, MY_ROLE, ORDER_ACTION } from '@/utils/orderDict'
import { formatMoney } from '@/utils/format'

const router = useRouter()

const activeTab = ref('buy')
const list = ref([])
const total = ref(0)
const loading = ref(false)
const query = ref({ pageNum: 1, pageSize: 10, orderStatus: null })

const statusOptions = computed(() =>
  Object.entries(ORDER_STATUS).map(([value, item]) => ({
    value: Number(value),
    label: item.label
  }))
)

async function load() {
  loading.value = true
  try {
    const params = { ...query.value }
    const res = activeTab.value === 'buy'
      ? await getMyPurchases(params)
      : await getMySales(params)
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
  query.value.orderStatus = null
  load()
})

watch(() => query.value.orderStatus, () => {
  query.value.pageNum = 1
  load()
})

onMounted(load)

/** 执行一个动作。除拒绝/取消外都是一步到底,不需要额外输入 */
async function runAction(order, action) {
  const isReject = action === 'reject'
  const isCancel = action === 'cancel'

  let reason = null
  if (isReject || isCancel) {
    try {
      const { value } = await ElMessageBox.prompt(
        isReject
          ? '买家会收到你的拒绝理由,请说明原因'
          : '请说明取消原因(选填)',
        isReject ? '拒绝该订单' : '取消订单',
        {
          confirmButtonText: '确定',
          cancelButtonText: '再想想',
          inputPlaceholder: isReject ? '例如:该时段资源已被预定' : '例如:临时改变计划',
          inputValidator: (v) => (isReject && !v?.trim()) ? '拒绝时必须填写原因' : true,
          type: 'warning'
        }
      )
      reason = value
    } catch {
      return
    }
  } else {
    const confirmText = {
      confirm: '确认接下这笔订单吗?确认后买家即可付款。',
      pay: `将模拟支付 ¥${formatMoney(order.payAmount)},其中押金 ¥${formatMoney(order.depositAmount)} 在订单完成后退还。`,
      deliver: '确认已将资源交付给买家?',
      receive: '确认已收到货物?确认后订单完成,押金将原路退还。',
      return: '确认已归还资源?确认后订单完成,押金将原路退还。'
    }[action]
    if (confirmText) {
      try {
        await ElMessageBox.confirm(confirmText, '请确认', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        })
      } catch {
        return
      }
    }
  }

  try {
    if (action === 'confirm') await confirmOrder(order.id)
    else if (action === 'reject') await rejectOrder(order.id, reason)
    else if (action === 'pay') await payOrder(order.id, 1)
    else if (action === 'deliver') await deliverOrder(order.id)
    else if (action === 'receive') await receiveOrder(order.id)
    else if (action === 'return') await returnOrder(order.id)
    else if (action === 'cancel') await cancelOrder(order.id, reason)
    ElMessage.success('操作成功')
    load()
  } catch (e) {
    // 业务错误已由 axios 拦截器统一提示
  }
}

function goDetail(id) {
  router.push(`/order/${id}`)
}

/** 订单的一句话摘要:让用户不点进去就知道这是什么 */
function summarize(order) {
  if (order.orderType === 2) {
    return `购买 · 应付 ¥${formatMoney(order.totalAmount)}`
  }
  return `${order.rentStartDate} 至 ${order.rentEndDate} · 共 ${order.rentDays} 天`
}
</script>

<template>
  <div class="page-container my-orders">
    <header class="page-head">
      <div>
        <h1>我的订单</h1>
        <p class="text-secondary">
          买家看"我买到的",卖家看"我卖出的"—— 同一个人可能两边都有
        </p>
      </div>
    </header>

    <el-tabs v-model="activeTab" class="order-tabs">
      <el-tab-pane label="我买到的" name="buy" />
      <el-tab-pane label="我卖出的" name="sell" />
    </el-tabs>

    <div class="filter-bar">
      <el-select
        v-model="query.orderStatus"
        placeholder="全部状态"
        clearable
        class="status-select"
      >
        <el-option
          v-for="opt in statusOptions"
          :key="opt.value"
          :label="opt.label"
          :value="opt.value"
        />
      </el-select>
      <span class="text-secondary text-sm">共 {{ total }} 笔</span>
    </div>

    <div v-loading="loading" class="order-list">
      <el-empty
        v-if="!loading && list.length === 0"
        :description="activeTab === 'buy' ? '还没有租过或买过东西' : '还没有人下单你的资源'"
      />

      <article
        v-for="order in list"
        :key="order.id"
        class="order-card card"
      >
        <header class="card-head">
          <div class="head-left">
            <span class="order-no" @click="goDetail(order.id)">{{ order.orderNo }}</span>
            <el-tag :type="ORDER_TYPE[order.orderType]?.type" size="small" effect="plain">
              {{ ORDER_TYPE[order.orderType]?.label }}
            </el-tag>
            <el-tag size="small" type="info" effect="plain">
              {{ MY_ROLE[order.myRole] }}
            </el-tag>
          </div>
          <el-tag :type="ORDER_STATUS[order.orderStatus]?.type" effect="dark" size="small">
            {{ ORDER_STATUS[order.orderStatus]?.label }}
          </el-tag>
        </header>

        <div class="card-body" @click="goDetail(order.id)">
          <img :src="order.coverUrl" :alt="order.resourceName" class="res-thumb" />
          <div class="body-info">
            <h3 class="res-name">{{ order.resourceName }}</h3>
            <p class="text-secondary text-sm">{{ summarize(order) }}</p>
            <p class="text-secondary text-sm">
              {{ order.myRole === 'buyer'
                ? `卖家:${order.sellerName}`
                : `买家:${order.buyerName}` }}
              · {{ order.villageName }}
            </p>
          </div>
          <div class="body-amount">
            <div class="amount-num">¥{{ formatMoney(order.payAmount) }}</div>
            <div v-if="Number(order.depositAmount) > 0" class="text-xs text-secondary">
              含押金 ¥{{ formatMoney(order.depositAmount) }}
            </div>
          </div>
        </div>

        <footer v-if="order.actions?.length" class="card-foot">
          <el-button
            v-for="action in order.actions"
            :key="action"
            :type="ORDER_ACTION[action]?.type"
            :plain="ORDER_ACTION[action]?.plain"
            size="small"
            @click="runAction(order, action)"
          >
            {{ ORDER_ACTION[action]?.label }}
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
  </div>
</template>

<style scoped>
.my-orders {
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

.order-tabs {
  margin-bottom: var(--space-md);
}

.order-tabs :deep(.el-tabs__header) {
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

.order-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
  min-height: 200px;
}

.order-card {
  padding: 0;
  overflow: hidden;
  transition: box-shadow var(--duration-fast);
}

.order-card:hover {
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

.order-no {
  font-family: var(--font-family-number);
  font-size: var(--font-size-sm);
  color: var(--color-text-regular);
  cursor: pointer;
}

.order-no:hover {
  color: var(--color-primary);
}

.card-body {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-md) var(--space-lg);
  cursor: pointer;
}

.res-thumb {
  width: 80px;
  height: 60px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  flex-shrink: 0;
}

.body-info {
  flex: 1;
  min-width: 0;
}

.res-name {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  margin-bottom: 2px;
}

.body-info p {
  margin: 2px 0;
}

.body-amount {
  text-align: right;
  flex-shrink: 0;
}

.amount-num {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-danger);
  font-family: var(--font-family-number);
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
</style>
