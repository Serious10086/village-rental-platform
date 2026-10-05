<script setup>
/**
 * 后台 —— 订单管理。
 *
 * 与前台"我的订单"的差别:这里看的是<b>本村(或全部)的订单</b>,
 * 而不是"我参与的订单"。村级管理员要能掌握本村的交易情况,
 * 出现纠纷时也是从这里调订单数据的。
 *
 * 数据级权限同样落在这里:query 里不接受前端传的 villageId,
 * 服务端会用 dataScopeHelper 覆盖掉。前端改地址栏参数不会越权。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { getAdminOrders, adminCancelOrder, getOrderDetail } from '@/api/order'
import { useUserStore } from '@/stores/user'
import { ORDER_STATUS, ORDER_TYPE, PAY_STATUS, MY_ROLE, buildTimeline } from '@/utils/orderDict'
import { formatDateTime, formatMoney } from '@/utils/format'

const userStore = useUserStore()

const loading = ref(false)
const rows = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  orderNo: '',
  orderStatus: null,
  orderType: null
})

const statusOptions = Object.entries(ORDER_STATUS).map(([v, o]) => ({
  value: Number(v), label: o.label
}))

const scopeText = {
  1: '全部村组的订单',
  2: '本镇范围内的订单',
  3: `本村(${userStore.userInfo?.villageName || ''})的订单`,
  4: '你本人参与的订单'
}[userStore.userInfo?.dataScope] || ''

// ── 详情抽屉 ──────────────────────────────────────────────────
const drawerVisible = ref(false)
const detail = ref(null)
const detailLoading = ref(false)

async function fetchList() {
  loading.value = true
  try {
    const data = await getAdminOrders({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      orderNo: query.orderNo || undefined,
      orderStatus: query.orderStatus ?? undefined,
      orderType: query.orderType ?? undefined
    })
    rows.value = data.rows || []
    total.value = data.total || 0
  } catch (e) {
    rows.value = []
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.orderNo = ''
  query.orderStatus = null
  query.orderType = null
  handleSearch()
}

async function openDetail(row) {
  drawerVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await getOrderDetail(row.id)
  } catch (e) {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

/**
 * 强制取消。
 *
 * 与买家自取消的差别是不受订单状态限制 —— 用于资源被封、
 * 卖家账号异常这类必须终止订单的场景。原因必填,双方都会收到。
 */
async function handleCancel(row) {
  try {
    const { value } = await ElMessageBox.prompt(
      `将强制取消订单 ${row.orderNo}。买卖双方都会收到你填写的原因。`,
      '强制取消订单',
      {
        confirmButtonText: '确认取消订单',
        cancelButtonText: '放弃',
        inputPlaceholder: '例如:该资源已被认定存在安全隐患,订单终止',
        inputValidator: (v) => (!v || !v.trim()) ? '必须填写原因' : true,
        type: 'warning'
      }
    )
    await adminCancelOrder(row.id, value)
    ElMessage.success('订单已强制取消')
    fetchList()
  } catch (e) {
    // 用户点了取消,或拦截器已提示业务错误
  }
}

onMounted(fetchList)
</script>

<template>
  <div class="admin-order">
    <el-alert type="info" :closable="false" show-icon class="scope-alert">
      <template #title>
        当前数据范围:<b>{{ scopeText }}</b>。
        过滤条件由服务端注入,手动修改地址栏参数不会越权。
      </template>
    </el-alert>

    <div class="card filter-bar">
      <el-input
        v-model="query.orderNo"
        placeholder="按订单号搜索"
        clearable
        style="width: 220px"
        @keyup.enter="handleSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select v-model="query.orderStatus" placeholder="订单状态" clearable style="width: 150px">
        <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>

      <el-select v-model="query.orderType" placeholder="订单类型" clearable style="width: 120px">
        <el-option label="租赁" :value="1" />
        <el-option label="购买" :value="2" />
      </el-select>

      <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
      <el-button :icon="Refresh" @click="handleReset">重置</el-button>

      <div class="spacer" />
      <span class="text-sm text-secondary">共 <b class="num">{{ total }}</b> 条</span>
    </div>

    <div class="card table-card">
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column label="订单号" width="180">
          <template #default="{ row }">
            <a class="order-no" @click="openDetail(row)">{{ row.orderNo }}</a>
            <div>
              <el-tag size="small" effect="plain" :type="ORDER_TYPE[row.orderType]?.type">
                {{ ORDER_TYPE[row.orderType]?.label }}
              </el-tag>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="资源" min-width="190">
          <template #default="{ row }">
            <div class="res-cell">
              <img :src="row.coverUrl" :alt="row.resourceName" class="thumb" />
              <span class="res-name">{{ row.resourceName }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="买方 / 卖方" min-width="160">
          <template #default="{ row }">
            <div class="party-line">
              <el-tag size="small" type="info" effect="plain">买</el-tag>
              <span>{{ row.buyerName }}</span>
            </div>
            <div class="party-line">
              <el-tag size="small" type="warning" effect="plain">卖</el-tag>
              <span>{{ row.sellerName }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="租期 / 类型" min-width="180">
          <template #default="{ row }">
            <template v-if="row.orderType === 1">
              <div class="num-text">{{ row.rentStartDate }} ~ {{ row.rentEndDate }}</div>
              <div class="text-xs text-secondary">共 {{ row.rentDays }} 天</div>
            </template>
            <span v-else class="text-secondary">一次性购买</span>
          </template>
        </el-table-column>

        <el-table-column label="金额" width="130" align="right">
          <template #default="{ row }">
            <div class="amount">¥{{ formatMoney(row.payAmount) }}</div>
            <div class="text-xs text-secondary">
              押金 ¥{{ formatMoney(row.depositAmount) }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="ORDER_STATUS[row.orderStatus]?.type" size="small" effect="dark">
              {{ ORDER_STATUS[row.orderStatus]?.label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="下单时间" width="150">
          <template #default="{ row }">
            <span class="num-text">{{ formatDateTime(row.createTime) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">
              查看
            </el-button>
            <!--
              按钮级权限:没有 order:cancel 的角色(比如只读的统计角色)
              看不到这个按钮。但**前端隐藏挡不住直接发 HTTP 请求** ——
              接口上还有 @RequiresPerm("order:cancel") 独立校验一次。
            -->
            <el-button
              v-if="![4, 5, 7].includes(row.orderStatus)"
              v-hasPerm="'order:cancel'"
              link
              type="danger"
              size="small"
              @click="handleCancel(row)"
            >
              强制取消
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="total > query.pageSize"
        v-model:current-page="query.pageNum"
        :page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        class="pager"
        @current-change="fetchList"
      />
    </div>

    <!-- 订单详情抽屉:后台不跳前台页面,避免管理员离开工作台 -->
    <el-drawer v-model="drawerVisible" title="订单详情" size="560px">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="订单号">
              <span class="num-text">{{ detail.orderNo }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="ORDER_STATUS[detail.orderStatus]?.type" size="small" effect="dark">
                {{ ORDER_STATUS[detail.orderStatus]?.label }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="资源">{{ detail.resourceName }}</el-descriptions-item>
            <el-descriptions-item label="租期">
              {{ detail.orderType === 1
                ? `${detail.rentStartDate} ~ ${detail.rentEndDate}(${detail.rentDays} 天)`
                : '一次性购买' }}
            </el-descriptions-item>
            <el-descriptions-item label="金额明细">
              单价 ¥{{ formatMoney(detail.unitPrice) }} · {{ detail.orderType === 1 ? '租金' : '货款' }}
              ¥{{ formatMoney(detail.totalAmount) }} · 押金 ¥{{ formatMoney(detail.depositAmount) }}
            </el-descriptions-item>
            <el-descriptions-item label="应付总额">
              <b class="text-danger">¥{{ formatMoney(detail.payAmount) }}</b>
            </el-descriptions-item>
            <el-descriptions-item label="支付状态">
              {{ PAY_STATUS[detail.payStatus] }}
            </el-descriptions-item>
            <el-descriptions-item label="买家">
              {{ detail.buyerName }}({{ detail.buyerPhone }},信用 {{ detail.buyerCredit }})
            </el-descriptions-item>
            <el-descriptions-item label="卖家">
              {{ detail.sellerName }}({{ detail.sellerPhone }},信用 {{ detail.sellerCredit }})
            </el-descriptions-item>
            <el-descriptions-item label="所属村组">{{ detail.villageName }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.buyerRemark" label="买家留言">
              {{ detail.buyerRemark }}
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.cancelReason" label="取消原因">
              {{ detail.cancelReason }}
            </el-descriptions-item>
          </el-descriptions>

          <h4 class="drawer-subtitle">订单流程</h4>
          <el-steps direction="vertical" :active="detail.orderStatus === 4 ? 99 : (ORDER_STATUS[detail.orderStatus]?.step || 0)">
            <el-step
              v-for="node in buildTimeline(detail)"
              :key="node.key"
              :title="node.title"
              :description="node.time || node.desc"
            />
          </el-steps>
        </template>
      </div>
    </el-drawer>
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

.spacer {
  flex: 1;
}

.table-card {
  padding: var(--space-md) var(--space-lg);
}

.order-no {
  font-family: var(--font-family-number);
  font-size: var(--font-size-sm);
  cursor: pointer;
  color: var(--color-primary);
}

.res-cell {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.thumb {
  width: 44px;
  height: 34px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  flex-shrink: 0;
}

.res-name {
  font-size: var(--font-size-sm);
}

.party-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--font-size-sm);
}

.amount {
  font-family: var(--font-family-number);
  font-weight: var(--font-weight-bold);
  color: var(--color-danger);
}

.num-text {
  font-family: var(--font-family-number);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.pager {
  margin-top: var(--space-md);
  justify-content: flex-end;
}

.drawer-subtitle {
  margin: var(--space-lg) 0 var(--space-md);
  font-size: var(--font-size-base);
}
</style>
