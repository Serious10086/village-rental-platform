<script setup>
/**
 * 我的发布 —— 发布者对资源的全部管理工作台。
 *
 * 与前台浏览页的关键差别:这里能看到自己资源的所有状态
 * (待审核、已下架、被驳回),而这些在公开列表里是被过滤掉的。
 * 所以它查的是基表,不是 v_resource_list 视图。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getMyResources, offlineResource, onlineResource,
  adjustPrice, deleteResource
} from '@/api/resource'
import { useUserStore } from '@/stores/user'
import { RESOURCE_STATUS, TRADE_TYPE, formatPrice } from '@/utils/resourceDict'
import { formatMoney } from '@/utils/format'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const rows = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  resourceStatus: null
})

/** 状态筛选项 —— 只列出发布者会关心的几个,不是全量枚举 */
const statusTabs = [
  { label: '全部', value: null },
  { label: '待审核', value: 0 },
  { label: '已上架', value: 1 },
  { label: '已下架', value: 2 },
  { label: '交易中', value: 3 },
  { label: '审核驳回', value: 5 }
]

/** 各状态的数量,用于标签上的角标 */
const statusCounts = computed(() => {
  const map = {}
  rows.value.forEach((r) => {
    map[r.resourceStatus] = (map[r.resourceStatus] || 0) + 1
  })
  return map
})

async function fetchList() {
  loading.value = true
  try {
    const data = await getMyResources({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      resourceStatus: query.resourceStatus ?? undefined
    })
    rows.value = data.rows || []
    total.value = data.total || 0
  } catch (e) {
    rows.value = []
  } finally {
    loading.value = false
  }
}

function handleTabChange() {
  query.pageNum = 1
  fetchList()
}

/** 下架/上架 */
async function toggleStatus(row) {
  const isOnline = row.resourceStatus === 1
  const action = isOnline ? '下架' : '上架'
  try {
    await ElMessageBox.confirm(
      isOnline
        ? '下架后该资源将从前台消失,已产生的订单不受影响。确定下架吗?'
        : '重新上架后,该资源会重新出现在前台。确定上架吗?',
      `确认${action}`,
      { confirmButtonText: action, cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  try {
    if (isOnline) {
      await offlineResource(row.id)
    } else {
      await onlineResource(row.id)
    }
    ElMessage.success(`${action}成功`)
    fetchList()
  } catch (e) {
    // 拦截器已提示
  }
}

/** 调价 */
async function handleAdjustPrice(row) {
  try {
    const { value } = await ElMessageBox.prompt(
      `当前价格:${formatPrice(row)}。请输入新价格:`,
      '调整价格',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        inputPattern: /^\d+(\.\d{1,2})?$/,
        inputErrorMessage: '请输入正确的金额(最多两位小数)',
        inputValue: String(row.price)
      }
    )
    await adjustPrice(row.id, Number(value))
    ElMessage.success('调价成功')
    fetchList()
  } catch (e) {
    // 用户取消,或调价失败(拦截器已提示)
  }
}

/** 删除 */
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.resourceName}」吗?删除后不可恢复。`,
      '确认删除',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteResource(row.id)
    ElMessage.success('已删除')
    fetchList()
  } catch (e) {
    // 拦截器已提示
  }
}

function goDetail(id) {
  router.push(`/resource/${id}`)
}

function goEdit(id) {
  router.push(`/publish/edit/${id}`)
}

/** 按钮可用性判断 —— 和后端 ResourceStatusEnum 的规则保持一致 */
const canEdit = (row) => [0, 1, 2, 5].includes(row.resourceStatus)
const canOffline = (row) => row.resourceStatus === 1
const canOnline = (row) => row.resourceStatus === 2
const canAdjustPrice = (row) => ![3, 4].includes(row.resourceStatus)
const canDelete = (row) => row.resourceStatus !== 3

onMounted(fetchList)
</script>

<template>
  <div class="page-container">
    <header class="page-head">
      <div>
        <h1 class="page-title">我的发布</h1>
        <p class="page-sub">管理你发布的所有闲置资源</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="router.push('/publish')">
        发布新资源
      </el-button>
    </header>

    <!-- 未实名提示 -->
    <el-alert
      v-if="!userStore.isRealnameVerified"
      type="warning"
      :closable="false"
      show-icon
      class="tip-alert"
      title="你还没有完成实名认证,无法发布新资源。已发布的资源仍然可以管理。"
    />

    <!-- 状态筛选 -->
    <el-tabs v-model="query.resourceStatus" class="status-tabs" @tab-change="handleTabChange">
      <el-tab-pane
        v-for="tab in statusTabs"
        :key="String(tab.value)"
        :name="tab.value"
      >
        <template #label>
          <span>{{ tab.label }}</span>
          <el-badge
            v-if="tab.value !== null && statusCounts[tab.value]"
            :value="statusCounts[tab.value]"
            class="tab-badge"
          />
        </template>
      </el-tab-pane>
    </el-tabs>

    <!-- 列表 -->
    <div v-loading="loading" class="list-wrap">
      <template v-if="rows.length">
        <div v-for="row in rows" :key="row.id" class="resource-row">
          <!-- 缩略图 -->
          <div class="thumb" @click="goDetail(row.id)">
            <img v-if="row.coverUrl" :src="row.coverUrl" :alt="row.resourceName" />
            <div v-else class="thumb-placeholder">
              <el-icon :size="22"><Picture /></el-icon>
            </div>
          </div>

          <!-- 主体信息 -->
          <div class="info">
            <div class="info-head">
              <a class="name" @click="goDetail(row.id)">{{ row.resourceName }}</a>
              <el-tag
                :type="RESOURCE_STATUS[row.resourceStatus]?.type"
                size="small"
                effect="light"
              >{{ RESOURCE_STATUS[row.resourceStatus]?.label }}</el-tag>
              <el-tag :type="TRADE_TYPE[row.tradeType]?.type" size="small" effect="plain">
                {{ TRADE_TYPE[row.tradeType]?.label }}
              </el-tag>
            </div>

            <div class="info-meta">
              <span>{{ row.categoryName }}</span>
              <span class="dot">·</span>
              <span>{{ row.villageName }}</span>
              <span class="dot">·</span>
              <span class="num">{{ row.viewCount }} 次浏览</span>
              <span class="dot">·</span>
              <span class="num">{{ row.orderCount }} 单成交</span>
            </div>

            <!-- 驳回/下架原因:必须显示,否则用户不知道该怎么办 -->
            <p v-if="row.offlineReason" class="reason">
              <el-icon :size="12"><WarningFilled /></el-icon>
              {{ RESOURCE_STATUS[row.resourceStatus]?.label }}原因:{{ row.offlineReason }}
            </p>
          </div>

          <!-- 价格 -->
          <div class="price-col">
            <div class="price num">{{ formatPrice(row) }}</div>
            <div v-if="row.tradeType === 1 && row.deposit > 0" class="deposit">
              押金 ¥{{ formatMoney(row.deposit) }}
            </div>
          </div>

          <!-- 操作 -->
          <div class="actions">
            <el-button v-if="canEdit(row)" link type="primary" size="small" @click="goEdit(row.id)">
              编辑
            </el-button>
            <el-button
              v-if="canAdjustPrice(row)"
              link
              type="primary"
              size="small"
              @click="handleAdjustPrice(row)"
            >调价</el-button>
            <el-button
              v-if="canOffline(row)"
              link
              type="warning"
              size="small"
              @click="toggleStatus(row)"
            >下架</el-button>
            <el-button
              v-if="canOnline(row)"
              link
              type="success"
              size="small"
              @click="toggleStatus(row)"
            >上架</el-button>
            <el-button
              v-if="canDelete(row)"
              link
              type="danger"
              size="small"
              @click="handleDelete(row)"
            >删除</el-button>
          </div>
        </div>

        <el-pagination
          v-if="total > query.pageSize"
          v-model:current-page="query.pageNum"
          :page-size="query.pageSize"
          :total="total"
          layout="prev, pager, next"
          class="pagination"
          @current-change="fetchList"
        />
      </template>

      <el-empty v-else-if="!loading" description="这里还没有资源">
        <el-button type="primary" @click="router.push('/publish')">去发布第一个资源</el-button>
      </el-empty>
    </div>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  margin-bottom: var(--space-lg);
  flex-wrap: wrap;
}

.page-title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.page-sub {
  margin-top: var(--space-xs);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.tip-alert {
  margin-bottom: var(--space-md);
}

.status-tabs {
  margin-bottom: var(--space-md);
}

.status-tabs :deep(.el-tabs__header) {
  margin-bottom: var(--space-md);
}

.tab-badge {
  margin-left: 6px;
}

.tab-badge :deep(.el-badge__content) {
  font-size: 10px;
}

.list-wrap {
  min-height: 300px;
}

/* ── 列表行 ──────────────────────────────────────────────── */
.resource-row {
  display: grid;
  grid-template-columns: 100px 1fr auto auto;
  gap: var(--space-md);
  align-items: center;
  padding: var(--space-md);
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  margin-bottom: var(--space-sm);
  transition: border-color var(--duration-fast);
}

.resource-row:hover {
  border-color: var(--color-primary-border);
}

.thumb {
  width: 100px;
  height: 75px;
  border-radius: var(--radius-sm);
  overflow: hidden;
  cursor: pointer;
  background: var(--color-info-light);
  flex-shrink: 0;
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-placeholder);
}

.info {
  min-width: 0;
}

.info-head {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.name {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  cursor: pointer;
  transition: color var(--duration-fast);
}

.name:hover {
  color: var(--color-primary);
}

.info-meta {
  margin-top: 6px;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

.dot {
  color: var(--color-text-disabled);
}

.reason {
  margin-top: 6px;
  font-size: var(--font-size-xs);
  color: var(--color-danger);
  display: flex;
  align-items: center;
  gap: 4px;
}

.price-col {
  text-align: right;
  flex-shrink: 0;
}

.price {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-danger);
}

.deposit {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  flex-shrink: 0;
  min-width: 64px;
}

.actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.pagination {
  margin-top: var(--space-lg);
  justify-content: center;
}

/* ── 响应式 ──────────────────────────────────────────────── */
@media (max-width: 768px) {
  .resource-row {
    grid-template-columns: 80px 1fr;
    grid-template-areas:
      'thumb info'
      'thumb price'
      'actions actions';
  }

  .thumb { grid-area: thumb; width: 80px; height: 60px; }
  .info { grid-area: info; }
  .price-col { grid-area: price; text-align: left; }
  .actions {
    grid-area: actions;
    flex-direction: row;
    justify-content: flex-start;
    gap: var(--space-sm);
  }
}
</style>
