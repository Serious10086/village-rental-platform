<script setup>
/**
 * 后台 —— 实名认证审核。
 *
 * 审核流程:申请人提交 → 村级管理员核对身份证照片与填写信息是否一致 →
 * 通过(账号可发布/下单)或驳回(必须写原因)。
 *
 * 页面上刻意突出三个信息:证件照、填写的姓名/身份证号、申请人所属村组。
 * 前两个是核对对象,第三个决定"该不该由我来审"。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getRealnameAuditList, auditRealname, getAuditDetail } from '@/api/audit'
import { AUDIT_STATUS } from '@/utils/auditDict'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

const loading = ref(false)
const rows = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  auditStatus: 0,   // 默认只看待审核 —— 管理员打开页面就是想处理待办
  keyword: ''
})

const statusTabs = [
  { label: '待审核', value: 0 },
  { label: '已通过', value: 1 },
  { label: '已驳回', value: 2 },
  { label: '全部', value: null }
]

/** 详情抽屉 */
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

/** 审核弹窗 */
const auditVisible = ref(false)
const auditSubmitting = ref(false)
const auditForm = reactive({
  auditId: null,
  approved: true,
  remark: ''
})

async function fetchList() {
  loading.value = true
  try {
    const data = await getRealnameAuditList({
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

/** 打开详情抽屉 —— 这里才会拿到完整身份证号 */
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
    ElMessage.warning('驳回时必须填写原因,否则申请人不知道该改什么')
    return
  }

  auditSubmitting.value = true
  try {
    await auditRealname({
      bizType: 1,
      auditId: auditForm.auditId,
      approved: auditForm.approved,
      remark: auditForm.remark.trim() || null
    })
    ElMessage.success(auditForm.approved ? '已通过' : '已驳回')
    auditVisible.value = false
    detailVisible.value = false
    fetchList()
  } catch (e) {
    // 拦截器已提示
  } finally {
    auditSubmitting.value = false
  }
}

/** 常用驳回理由 —— 一键填入,省得每次手打 */
const quickReasons = [
  '身份证照片模糊,请重新上传清晰照片',
  '姓名与身份证号不一致,请核对后重新提交',
  '身份证照片反光/遮挡,关键信息无法辨认',
  '请上传身份证原件照片,不要上传复印件或屏幕截图'
]

function useQuickReason(text) {
  auditForm.remark = text
}

onMounted(fetchList)
</script>

<template>
  <div class="audit-page">
    <el-alert type="info" :closable="false" show-icon class="scope-alert">
      <template #title>
        你只能审核 <b>本村</b> 的实名申请。
        列表里的身份证号已脱敏,点「查看」才会显示完整号码 —— 用于核对照片与填写是否一致。
      </template>
    </el-alert>

    <!-- 筛选 -->
    <div class="card filter-bar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索姓名或手机号"
        clearable
        style="width: 200px"
        @keyup.enter="handleTabChange"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button type="primary" :icon="Search" @click="handleTabChange">查询</el-button>
      <div class="spacer" />
      <span class="text-sm text-secondary">共 <b class="num">{{ total }}</b> 条</span>
    </div>

    <!-- 状态标签页 -->
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
        <el-table-column label="申请人" min-width="160">
          <template #default="{ row }">
            <div class="person-cell">
              <el-avatar :size="36">{{ (row.applicantName || '?').charAt(0) }}</el-avatar>
              <div>
                <div class="person-name">{{ row.applicantName }}</div>
                <div class="text-xs text-secondary num">{{ row.applicantPhone }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="applicantVillageName" label="所属村组" width="110" />

        <el-table-column label="身份证号" width="180">
          <template #default="{ row }">
            <span class="num text-sm">{{ row.idCard || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="信用分" width="90" align="center">
          <template #default="{ row }">
            <span class="num">{{ row.applicantCredit ?? '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="提交时间" width="160">
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

        <el-table-column label="审核意见" min-width="160">
          <template #default="{ row }">
            <span v-if="row.auditRemark" class="text-sm">{{ row.auditRemark }}</span>
            <span v-else class="text-xs text-secondary">—</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">
              查看
            </el-button>

            <!--
              只对待审核的单子显示通过/驳回。
              已处理的单子再审核会被后端拒绝 —— 前端也一并藏掉,
              避免管理员点了才收到"已被处理"的提示。
            -->
            <template v-if="row.auditStatus === 0">
              <el-button link type="success" size="small" @click="openAudit(row, true)">
                通过
              </el-button>
              <el-button link type="danger" size="small" @click="openAudit(row, false)">
                驳回
              </el-button>
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
    <el-drawer v-model="detailVisible" title="实名认证详情" size="480px">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="姓名">
              <b>{{ detail.applicantName }}</b>
            </el-descriptions-item>
            <el-descriptions-item label="手机号">
              <span class="num">{{ detail.applicantPhone }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="身份证号">
              <span class="num">{{ detail.idCard }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="所属村组">
              {{ detail.applicantVillageName }}
            </el-descriptions-item>
            <el-descriptions-item label="信用分">
              <span class="num">{{ detail.applicantCredit }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="提交时间">
              <span class="num">
                {{ String(detail.submitTime || '').replace('T', ' ').slice(0, 19) }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.auditRemark" label="审核意见">
              {{ detail.auditRemark }}
            </el-descriptions-item>
          </el-descriptions>

          <h4 class="img-title">证件照</h4>
          <div class="id-images">
            <div class="id-image">
              <img v-if="detail.idCardFront" :src="detail.idCardFront" alt="身份证正面" />
              <div v-else class="no-image">未上传</div>
              <span class="img-label">人像面</span>
            </div>
            <div class="id-image">
              <img v-if="detail.idCardBack" :src="detail.idCardBack" alt="身份证反面" />
              <div v-else class="no-image">未上传</div>
              <span class="img-label">国徽面</span>
            </div>
          </div>

          <el-alert
            v-if="detail.auditStatus === 0"
            type="warning"
            :closable="false"
            show-icon
            class="drawer-tip"
            title="请核对证件照上的姓名、号码与上面填写的是否一致"
          />

          <div v-if="detail.auditStatus === 0" class="drawer-actions">
            <el-button type="success" @click="openAudit(detail, true)">通过</el-button>
            <el-button type="danger" @click="openAudit(detail, false)">驳回</el-button>
          </div>
        </template>
      </div>
    </el-drawer>

    <!-- ── 审核弹窗 ──────────────────────────────────────── -->
    <el-dialog
      v-model="auditVisible"
      :title="auditForm.approved ? '确认通过实名认证' : '驳回实名认证'"
      width="520px"
    >
      <el-alert
        :type="auditForm.approved ? 'success' : 'warning'"
        :closable="false"
        show-icon
        class="dialog-alert"
        :title="auditForm.approved
          ? '通过后,该用户即可发布资源和下单'
          : '驳回后,用户需要修改资料重新提交才能再次审核'"
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
              : '请说明具体问题,用户会收到这条原因'"
          />
        </el-form-item>
      </el-form>

      <!-- 常用理由快捷填入 -->
      <div v-if="!auditForm.approved" class="quick-reasons">
        <div class="quick-title">常用理由(点击填入)</div>
        <div class="quick-list">
          <el-tag
            v-for="r in quickReasons"
            :key="r"
            size="small"
            effect="plain"
            class="quick-tag"
            @click="useQuickReason(r)"
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

.person-cell {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.person-name {
  font-weight: var(--font-weight-medium);
  line-height: 1.3;
}

.pagination {
  margin-top: var(--space-md);
  justify-content: flex-end;
}

/* ── 详情抽屉 ────────────────────────────────────────────── */
.img-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  margin: var(--space-lg) 0 var(--space-sm);
}

.id-images {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-sm);
}

.id-image {
  position: relative;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--color-info-light);
}

.id-image img {
  width: 100%;
  aspect-ratio: 8 / 5;
  object-fit: cover;
}

.no-image {
  aspect-ratio: 8 / 5;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-placeholder);
  font-size: var(--font-size-xs);
}

.img-label {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  background: rgba(15, 23, 42, 0.7);
  color: #fff;
  font-size: 11px;
  text-align: center;
  padding: 2px 0;
}

.drawer-tip {
  margin-top: var(--space-md);
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
