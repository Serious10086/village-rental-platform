<script setup>
/**
 * 后台 —— 资源管理。
 *
 * 与前台列表的差别:能看到全部状态的资源(待审核、已下架、违规),
 * 且受数据级权限约束 —— 村级管理员只能看到本村。
 * 页面顶部会明确标出当前的数据范围,让管理员清楚自己看的是哪一部分。
 */
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getAdminResources } from '@/api/resource'
import { useUserStore } from '@/stores/user'
import {
  RESOURCE_STATUS, TRADE_TYPE, RISK_LEVEL, formatPrice, formatWeekdays
} from '@/utils/resourceDict'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const rows = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  resourceStatus: null,
  tradeType: null,
  categoryId: null
})

const statusOptions = Object.entries(RESOURCE_STATUS).map(([v, o]) => ({
  value: Number(v), label: o.label
}))

/** 数据范围提示文案 */
const scopeText = {
  1: '全部村组的资源',
  2: '本镇范围内的资源',
  3: `本村(${userStore.userInfo?.villageName || ''})的资源`,
  4: '你本人发布的资源'
}[userStore.userInfo?.dataScope] || ''

async function fetchList() {
  loading.value = true
  try {
    const data = await getAdminResources({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined,
      resourceStatus: query.resourceStatus ?? undefined,
      tradeType: query.tradeType ?? undefined,
      categoryId: query.categoryId ?? undefined
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
  query.keyword = ''
  query.resourceStatus = null
  query.tradeType = null
  query.categoryId = null
  handleSearch()
}

function goDetail(id) {
  // 从后台点进详情,看完返回时回到后台列表
  router.push({ path: `/resource/${id}`, query: { from: 'admin' } })
}

onMounted(fetchList)
</script>

<template>
  <div class="admin-resource">
    <!-- 数据范围说明 —— 数据级权限的可视化 -->
    <el-alert type="info" :closable="false" show-icon class="scope-alert">
      <template #title>
        当前数据范围:<b>{{ scopeText }}</b>。
        你只能看到范围内村组的资源,即使手动修改地址栏参数也不会越权 —— 过滤是服务端做的。
      </template>
    </el-alert>

    <!-- 筛选 -->
    <div class="card filter-bar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索资源名称"
        clearable
        style="width: 200px"
        @keyup.enter="handleSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select v-model="query.resourceStatus" placeholder="资源状态" clearable style="width: 140px">
        <el-option
          v-for="s in statusOptions"
          :key="s.value"
          :label="s.label"
          :value="s.value"
        />
      </el-select>

      <el-select v-model="query.tradeType" placeholder="交易方式" clearable style="width: 120px">
        <el-option label="出租" :value="1" />
        <el-option label="出售" :value="2" />
      </el-select>

      <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
      <el-button :icon="Refresh" @click="handleReset">重置</el-button>

      <div class="spacer" />
      <span class="text-sm text-secondary">共 <b class="num">{{ total }}</b> 条</span>
    </div>

    <!-- 表格 -->
    <div class="card table-card">
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column label="资源" min-width="240">
          <template #default="{ row }">
            <div class="res-cell">
              <div class="thumb">
                <img v-if="row.coverUrl" :src="row.coverUrl" :alt="row.resourceName" />
                <div v-else class="thumb-ph"><el-icon><Picture /></el-icon></div>
              </div>
              <div class="res-info">
                <a class="res-name" @click="goDetail(row.id)">{{ row.resourceName }}</a>
                <div class="res-meta num">{{ row.resourceNo }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="分类 / 位置" min-width="150">
          <template #default="{ row }">
            <div>{{ row.categoryName }}</div>
            <div class="text-xs text-secondary">{{ row.location }}</div>
          </template>
        </el-table-column>

        <el-table-column label="所属村组" width="110">
          <template #default="{ row }">{{ row.villageName }}</template>
        </el-table-column>

        <el-table-column label="发布人" width="110">
          <template #default="{ row }">
            <div class="pub-cell">
              <span>{{ row.publisherName }}</span>
              <span class="text-xs credit num">信用 {{ row.publisherCredit }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="交易" width="90">
          <template #default="{ row }">
            <el-tag :type="TRADE_TYPE[row.tradeType]?.type" size="small" effect="plain">
              {{ TRADE_TYPE[row.tradeType]?.label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="价格" width="120" align="right">
          <template #default="{ row }">
            <span class="num price">{{ formatPrice(row) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="风险" width="90" align="center">
          <template #default="{ row }">
            <el-tag
              v-if="row.riskLevel >= 2"
              :type="RISK_LEVEL[row.riskLevel]?.type"
              size="small"
              effect="dark"
            >{{ RISK_LEVEL[row.riskLevel]?.label }}</el-tag>
            <span v-else class="text-xs text-secondary">低</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="RESOURCE_STATUS[row.resourceStatus]?.type" size="small">
              {{ RESOURCE_STATUS[row.resourceStatus]?.label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="数据" width="100" align="right">
          <template #default="{ row }">
            <div class="num text-xs">{{ row.viewCount }} 浏览</div>
            <div class="num text-xs">{{ row.orderCount }} 单</div>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="goDetail(row.id)">
              查看
            </el-button>
            <!--
              审核与下架按钮在批次 4(审核流)实现。
              这里先按权限控制显隐,验证按钮级权限已经生效:
              村级管理员有 resource:offline 但没有 resource:audit 之外的能力差异,
              超管则全都有。
            -->
            <el-button
              v-if="userStore.hasPerm('resource:offline') && row.resourceStatus === 1"
              link
              type="danger"
              size="small"
              disabled
            >下架</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        class="pagination"
        @size-change="handleSearch"
        @current-change="fetchList"
      />
    </div>
  </div>
</template>

<style scoped>
.admin-resource {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.scope-alert :deep(.el-alert__title) {
  line-height: 1.7;
  font-size: var(--font-size-sm);
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
  padding: var(--space-md) var(--space-lg);
}

.spacer {
  flex: 1;
}

.table-card {
  padding: var(--space-md) var(--space-lg) var(--space-lg);
}

.res-cell {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.thumb {
  width: 56px;
  height: 42px;
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--color-info-light);
  flex-shrink: 0;
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-ph {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-placeholder);
}

.res-info {
  min-width: 0;
}

.res-name {
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  cursor: pointer;
}

.res-name:hover {
  color: var(--color-primary);
}

.res-meta {
  font-size: 11px;
  color: var(--color-text-placeholder);
}

.pub-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}

.credit {
  color: var(--color-success);
}

.price {
  font-weight: var(--font-weight-medium);
  color: var(--color-danger);
}

.pagination {
  margin-top: var(--space-md);
  justify-content: flex-end;
}
</style>
