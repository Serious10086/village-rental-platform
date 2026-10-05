<script setup>
/**
 * 后台 —— 资源发布审核。
 *
 * 审核的是"这条资源信息是否真实、合规、无安全隐患"。
 * 页面上突出:资源图片(判断实物是否存在)、描述(判断是否夸大)、
 * 风险等级(高风险资源要额外确认安全须知是否写清楚)。
 */
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getResourceAuditList, auditResource, getAuditDetail } from '@/api/audit'
import { AUDIT_STATUS } from '@/utils/auditDict'
import { TRADE_TYPE, RISK_LEVEL } from '@/utils/resourceDict'
import { formatMoney } from '@/utils/format'

const router = useRouter()

const loading = ref(false)
const rows = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  auditStatus: 0,
  keyword: ''
})

const statusTabs = [
  { label: '待审核', value: 0 },
  { label: '已通过', value: 1 },
  { label: '已驳回', value: 2 },
  { label: '全部', value: null }
]

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

const auditVisible = ref(false)
const auditSubmitting = ref(false)
const auditForm = reactive({
  auditId: null,
  approved: true,
  remark: ''
})

/** 驳回理由快捷选项 —— 资源审核的常见问题 */
const quickReasons = [
  '图片与描述不符,请上传资源实拍照片',
  '描述信息过于简单,请补充规格、新旧程度等关键信息',
  '价格明显偏离市场行情,请核实后重新填写',
  '该资源存在安全隐患,请补充安全使用说明',
  '分类选择有误,请选择正确的资源分类',
  '联系方式写在描述里,请通过平台沟通'
]

async function fetchList() {
  loading.value = true
  try {
    const data = await getResourceAuditList({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      auditStatus: query.auditStatus ?? undefined,
      keyword: query.keyword || undefined
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

async function openDetail(row) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getAuditDetail(row.id)
  } catch (e) {
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

function openAudit(row, approved) {
  auditForm.auditId = row.id
  auditForm.approved = approved
  auditForm.remark = ''
  auditVisible.value = true
}

async function submitAudit() {
  if (!auditForm.approved && !auditForm.remark.trim()) {
    ElMessage.warning('驳回时必须填写原因,发布者会看到这条原因')
    return
  }

  auditSubmitting.value = true
  try {
    await auditResource({
      bizType: 2,
      auditId: auditForm.auditId,
      approved: auditForm.approved,
      remark: auditForm.remark.trim() || null
    })
    ElMessage.success(auditForm.approved ? '已通过,资源已上架' : '已驳回')
    auditVisible.value = false
    detailVisible.value = false
    fetchList()
  } catch (e) {
    // 拦截器已提示
  } finally {
    auditSubmitting.value = false
  }
}

function goResource(bizId) {
  window.open(`/resource/${bizId}`, '_blank')
}

onMounted(fetchList)
</script>

<template>
  <div class="audit-page">
    <el-alert type="info" :closable="false" show-icon class="scope-alert">
      <template #title>
        你只能审核 <b>本村</b> 发布的资源。
        通过后资源立即在前台可见;驳回时请写明原因 —— 发布者在「我的发布」里能看到,修改后可重新提交。
      </template>
    </el-alert>

    <!-- 筛选 -->
    <div class="card filter-bar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索资源名称或发布人"
        clearable
        style="width: 220px"
        @keyup.enter="handleTabChange"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button type="primary" :icon="Search" @click="handleTabChange">查询</el-button>
      <div class="spacer" />
      <span class="text-sm text-secondary">共 <b class="num">{{ total }}</b> 条</span>
    </div>

    <el-tabs v-model="query.auditStatus" class="status-tabs" @tab-change="handleTabChange">
      <el-tab-pane
        v-for="tab in statusTabs"
        :key="String(tab.value)"
        :name="tab.value"
        :label="tab.label"
      />
    </el-tabs>

    <!-- 表格 -->
    <div class="card table-card">
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column label="资源" min-width="240">
          <template #default="{ row }">
            <div class="res-cell">
              <div class="thumb">
                <img v-if="row.resourceCover" :src="row.resourceCover" :alt="row.resourceName" />
                <div v-else class="thumb-ph"><el-icon><Picture /></el-icon></div>
              </div>
              <div class="res-info">
                <a class="res-name" @click="openDetail(row)">{{ row.resourceName }}</a>
                <div class="res-meta">
                  <el-tag
                    v-if="row.tradeType"
                    :type="TRADE_TYPE[row.tradeType]?.type"
                    size="small"
                    effect="plain"
                  >{{ TRADE_TYPE[row.tradeType]?.label }}</el-tag>
                  <span class="text-xs text-secondary">{{ row.categoryName }}</span>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="价格" width="120" align="right">
          <template #default="{ row }">
            <span class="num price">
              ¥{{ formatMoney(row.price) }}{{ row.tradeType === 1 ? '/天' : '' }}
            </span>
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

        <el-table-column label="发布人" width="130">
          <template #default="{ row }">
            <div class="text-sm">{{ row.applicantName }}</div>
            <div class="text-xs text-secondary num">信用 {{ row.applicantCredit }}</div>
          </template>
        </el-table-column>

        <el-table-column label="提交时间" width="150">
          <template #default="{ row }">
            <span class="num text-xs">
              {{ String(row.submitTime || '').replace('T', ' ').slice(0, 16) }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="AUDIT_STATUS[row.auditStatus]?.type" size="small">
              {{ AUDIT_STATUS[row.auditStatus]?.label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">查看</el-button>
            <template v-if="row.auditStatus === 0">
              <el-button link type="success" size="small" @click="openAudit(row, true)">通过</el-button>
              <el-button link type="danger" size="small" @click="openAudit(row, false)">驳回</el-button>
            </template>
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
        @size-change="handleTabChange"
        @current-change="fetchList"
      />
    </div>

    <!-- ── 详情抽屉 ──────────────────────────────────────── -->
    <el-drawer v-model="detailVisible" title="资源审核详情" size="560px">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div v-if="detail.resourceCover" class="detail-cover">
            <img :src="detail.resourceCover" :alt="detail.resourceName" />
          </div>

          <h3 class="detail-title">{{ detail.resourceName }}</h3>

          <div class="detail-tags">
            <el-tag :type="TRADE_TYPE[detail.tradeType]?.type" size="small">
              {{ TRADE_TYPE[detail.tradeType]?.label }}
            </el-tag>
            <el-tag size="small" effect="plain">{{ detail.categoryName }}</el-tag>
            <el-tag
              v-if="detail.riskLevel >= 2"
              :type="RISK_LEVEL[detail.riskLevel]?.type"
              size="small"
              effect="dark"
            >{{ RISK_LEVEL[detail.riskLevel]?.label }}</el-tag>
          </div>

          <el-descriptions :column="2" border size="small" class="detail-desc">
            <el-descriptions-item label="价格">
              <span class="num price">¥{{ formatMoney(detail.price) }}{{ detail.tradeType === 1 ? '/天' : '' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="发布人">
              {{ detail.applicantName }}
            </el-descriptions-item>
            <el-descriptions-item label="所属村组">
              {{ detail.applicantVillageName }}
            </el-descriptions-item>
            <el-descriptions-item label="信用分">
              <span class="num">{{ detail.applicantCredit }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <h4 class="block-title">资源描述</h4>
          <p class="description">{{ detail.resourceDescription || '发布者没有填写描述' }}</p>

          <el-alert
            v-if="detail.riskLevel === 3"
            type="error"
            :closable="false"
            show-icon
            class="risk-alert"
            title="这是高风险资源"
            description="请重点确认描述里是否写清了安全使用要求和操作资质要求"
          />

          <el-alert
            v-if="detail.auditStatus === 0"
            type="warning"
            :closable="false"
            show-icon
            class="drawer-tip"
            title="请确认图片与描述相符、无虚假信息、无安全隐患"
          />

          <div v-if="detail.bizId" class="detail-link">
            <el-button link type="primary" @click="goResource(detail.bizId)">
              在前台查看该资源(新窗口打开)
            </el-button>
          </div>

          <div v-if="detail.auditStatus === 0" class="drawer-actions">
            <el-button type="success" @click="openAudit(detail, true)">通过并上架</el-button>
            <el-button type="danger" @click="openAudit(detail, false)">驳回</el-button>
          </div>
        </template>
      </div>
    </el-drawer>

    <!-- ── 审核弹窗 ──────────────────────────────────────── -->
    <el-dialog
      v-model="auditVisible"
      :title="auditForm.approved ? '确认通过并上架' : '驳回资源'"
      width="540px"
    >
      <el-alert
        :type="auditForm.approved ? 'success' : 'warning'"
        :closable="false"
        show-icon
        class="dialog-alert"
        :title="auditForm.approved
          ? '通过后资源立即在前台可见,可以被搜索和下单'
          : '驳回后资源不会上架,发布者会收到通知并可修改后重新提交'"
      />

      <el-form label-position="top">
        <el-form-item :label="auditForm.approved ? '审核备注(选填)' : '驳回原因(必填)'">
          <el-input
            v-model="auditForm.remark"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :placeholder="auditForm.approved
              ? '可以留空'
              : '请说明具体问题,发布者会看到这条原因'"
          />
        </el-form-item>
      </el-form>

      <div v-if="!auditForm.approved" class="quick-reasons">
        <div class="quick-title">常见驳回理由(点击填入)</div>
        <div class="quick-list">
          <el-tag
            v-for="r in quickReasons"
            :key="r"
            size="small"
            effect="plain"
            class="quick-tag"
            @click="auditForm.remark = r"
          >{{ r }}</el-tag>
        </div>
      </div>

      <template #footer>
        <el-button @click="auditVisible = false">取消</el-button>
        <el-button
          :type="auditForm.approved ? 'success' : 'danger'"
          :loading="auditSubmitting"
          @click="submitAudit"
        >
          {{ auditForm.approved ? '确认通过' : '确认驳回' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.audit-page {
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
  padding: var(--space-md) var(--space-lg);
}

.spacer {
  flex: 1;
}

.status-tabs {
  margin-bottom: calc(var(--space-md) * -1);
}

.status-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
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
  width: 64px;
  height: 48px;
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
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
}

.price {
  font-weight: var(--font-weight-medium);
  color: var(--color-danger);
}

.pagination {
  margin-top: var(--space-md);
  justify-content: flex-end;
}

/* ── 抽屉 ────────────────────────────────────────────────── */
.detail-cover {
  border-radius: var(--radius-md);
  overflow: hidden;
  margin-bottom: var(--space-md);
}

.detail-cover img {
  width: 100%;
  aspect-ratio: 4 / 3;
  object-fit: cover;
}

.detail-title {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  line-height: 1.4;
}

.detail-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: var(--space-sm);
}

.detail-desc {
  margin-top: var(--space-md);
}

.block-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  margin: var(--space-lg) 0 var(--space-sm);
}

.description {
  font-size: var(--font-size-sm);
  line-height: 1.9;
  color: var(--color-text-regular);
  white-space: pre-wrap;
}

.risk-alert {
  margin-top: var(--space-md);
}

.drawer-tip {
  margin-top: var(--space-md);
}

.detail-link {
  margin-top: var(--space-sm);
}

.drawer-actions {
  margin-top: var(--space-lg);
  display: flex;
  gap: var(--space-sm);
}

.drawer-actions .el-button {
  flex: 1;
}

/* ── 弹窗 ────────────────────────────────────────────────── */
.dialog-alert {
  margin-bottom: var(--space-md);
}

.quick-reasons {
  margin-top: calc(var(--space-md) * -1 + 4px);
}

.quick-title {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  margin-bottom: var(--space-sm);
}

.quick-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.quick-tag {
  cursor: pointer;
  transition: all var(--duration-fast);
}

.quick-tag:hover {
  background: var(--color-primary-light);
  border-color: var(--color-primary);
  color: var(--color-primary);
}
</style>
