<script setup>
/**
 * 后台 —— 外来用户备案。
 *
 * 与实名审核的关键区别:这条流**没有申请人**。
 * 外来人员没有账号,是管理员在村委会当面替他建号、替他提交备案的,
 * 所以这个页面既有"登记"又有"审核"两块 —— 换一个村管登录,
 * 他看到的是别人登记的单子,流程就退回成和实名审核一样的形状。
 *
 * 备案解决的是「他归属哪个村」,不是「他是谁」:
 * 建号时 village_id 是空的,只有备案通过才写进去。
 * 在此之前他登录后处处受限(发布、下单、提现都卡在"没有归属村")。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
// Search 要显式引入:模板里 <Search /> 靠 main.js 的全局注册能渲染,
// 但 :icon="Search" 是 JS 表达式,取的是 setup 作用域,不引就是 undefined
import { Search } from '@element-plus/icons-vue'
import { getOutsideAuditList, createOutsideUser, auditOutside, getAuditDetail } from '@/api/audit'
import { getVillageList } from '@/api/auth'
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

/** 登记弹窗 */
const createVisible = ref(false)
const createSubmitting = ref(false)
const createFormRef = ref(null)
const createForm = reactive({
  phone: '',
  realName: '',
  villageId: null,
  address: ''
})

const createRules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  realName: [
    { required: true, message: '请输入姓名', trigger: 'blur' },
    { max: 20, message: '姓名不能超过 20 个字', trigger: 'blur' }
  ],
  villageId: [{ required: true, message: '请选择归属村组', trigger: 'change' }],
  address: [{ max: 200, message: '地址不能超过 200 个字', trigger: 'blur' }]
}

/** 可选的村 —— 公开接口只返回启用中的节点,但里面混着乡镇,下面再筛掉 */
const villages = ref([])

/**
 * 本村管理员没有选择权:归属村组锁死在他自己的村,下拉置灰。
 *
 * 后端反正会用 canManageVillage 拦一次,前端这一步只是别让他白填一整个表单
 * 再收到 403。
 */
const lockedVillageId = computed(() =>
  userStore.userInfo?.dataScope === 3 ? userStore.userInfo?.villageId : null
)

async function fetchVillages() {
  try {
    const list = await getVillageList()
    // parentId 为空的是乡镇(顶级节点)。外来人员要落户的是村,
    // 把乡镇放进下拉框是错的解 —— 选了它后端的外键不认,人也落不了户
    villages.value = (list || []).filter((v) => v.parentId != null)
  } catch (e) {
    villages.value = []
  }
}

async function fetchList() {
  loading.value = true
  try {
    const data = await getOutsideAuditList({
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

/** 打开详情抽屉 */
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
    ElMessage.warning('驳回时必须填写原因,否则登记人不知道该改什么')
    return
  }

  auditSubmitting.value = true
  try {
    await auditOutside({
      bizType: 3,
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

function openCreate() {
  createForm.phone = ''
  createForm.realName = ''
  createForm.address = ''
  createForm.villageId = lockedVillageId.value
  createVisible.value = true
  createFormRef.value?.clearValidate()
}

async function submitCreate() {
  if (!createFormRef.value) return
  try {
    await createFormRef.value.validate()
  } catch {
    return
  }

  createSubmitting.value = true
  try {
    await createOutsideUser({ ...createForm })
    ElMessage.success('已登记。初始密码 123456,请通过可靠渠道告知本人')
    createVisible.value = false
    // 新建的单子一定是待审核的,把页签切回来,否则管理员会以为提交失败了
    query.auditStatus = 0
    query.pageNum = 1
    fetchList()
  } catch (e) {
    // 拦截器已提示
  } finally {
    createSubmitting.value = false
  }
}

/** 常用驳回理由 —— 一键填入,省得每次手打 */
const quickReasons = [
  '手机号已有账号,请核实是否为同一个人',
  '姓名与本人证件不符,请核对后重新登记',
  '该人员不属于本村,请选择正确的归属村组',
  '登记信息不完整,请补充核实后再提交'
]

function useQuickReason(text) {
  auditForm.remark = text
}

onMounted(() => {
  fetchVillages()
  fetchList()
})
</script>

<template>
  <div class="outside-page">
    <el-alert type="info" :closable="false" show-icon class="scope-alert">
      <template #title>
        外来用户是<b>管理员代建的账号</b>:登记后先进待审核,点「通过」才把这个人的归属村组定下来。
        在此之前他登录后发不了资源、下不了单 —— 不是系统坏了,是还没有村。
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

      <!--
        按钮级权限:没有 user:outside 的角色看不到「新增备案」。
        这里用 v-if 而不是 v-hasPerm 指令 —— 后者只在 mounted 时把节点摘掉,
        而这排按钮的显隐还跟其他响应式状态有关,摘了就再也回不来。
      -->
      <el-button
        v-if="userStore.hasPerm('user:outside')"
        type="primary"
        @click="openCreate"
      >
        新增备案
      </el-button>
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
        <el-table-column label="被登记人" min-width="160">
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

        <!--
          这一列是「备案的目标村组」,不是这个人当前的归属 ——
          待审核时他身上的 village_id 还是空的,通过之后两者才重合
        -->
        <el-table-column prop="applicantVillageName" label="归属村组" width="110">
          <template #default="{ row }">
            <span v-if="row.applicantVillageName">{{ row.applicantVillageName }}</span>
            <span v-else class="text-secondary">—</span>
          </template>
        </el-table-column>

        <el-table-column label="信用分" width="90" align="center">
          <template #default="{ row }">
            <span class="num">{{ row.applicantCredit ?? '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="登记时间" width="160">
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
              只对待审核的单子显示通过/驳回。已处理的再审核会被后端拒绝,
              前端也一并藏掉,避免管理员点了才收到"已被处理"的提示。
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
    <el-drawer v-model="detailVisible" title="外来用户备案详情" size="440px">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="姓名">
              <b>{{ detail.applicantName }}</b>
            </el-descriptions-item>
            <el-descriptions-item label="手机号">
              <span class="num">{{ detail.applicantPhone }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="归属村组">
              {{ detail.applicantVillageName || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="信用分">
              <span class="num">{{ detail.applicantCredit }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="登记时间">
              <span class="num">
                {{ String(detail.submitTime || '').replace('T', ' ').slice(0, 19) }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="AUDIT_STATUS[detail.auditStatus]?.type" size="small">
                {{ AUDIT_STATUS[detail.auditStatus]?.label }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.auditorName" label="审核人">
              {{ detail.auditorName }}
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.auditRemark" label="审核意见">
              {{ detail.auditRemark }}
            </el-descriptions-item>
          </el-descriptions>

          <el-alert
            v-if="detail.auditStatus === 0"
            type="warning"
            :closable="false"
            show-icon
            class="drawer-tip"
            title="请核实这个人的身份和归属村组。通过后他会立刻获得该村用户的服务;驳回则保持无归属"
          />

          <div v-if="detail.auditStatus === 0" class="drawer-actions">
            <el-button type="success" @click="openAudit(detail, true)">通过</el-button>
            <el-button type="danger" @click="openAudit(detail, false)">驳回</el-button>
          </div>
        </template>
      </div>
    </el-drawer>

    <!-- ── 登记弹窗 ──────────────────────────────────────── -->
    <el-dialog v-model="createVisible" title="登记外来用户" width="520px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="dialog-alert"
        title="登记后先进入待审核。此时账号已建好,但归属村组要等备案通过才写入"
      />

      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="96px">
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="createForm.phone" maxlength="11" placeholder="本人的手机号,用于登录" />
        </el-form-item>

        <el-form-item label="姓名" prop="realName">
          <el-input v-model="createForm.realName" maxlength="20" placeholder="本人真实姓名" />
        </el-form-item>

        <el-form-item label="归属村组" prop="villageId">
          <el-select
            v-model="createForm.villageId"
            placeholder="请选择备案的目标村组"
            :disabled="lockedVillageId != null"
            style="width: 100%"
          >
            <el-option
              v-for="v in villages"
              :key="v.id"
              :label="v.villageName"
              :value="v.id"
            />
          </el-select>
          <div v-if="lockedVillageId != null" class="field-tip">
            你的数据范围是「本村」,归属村组固定为
            {{ userStore.userInfo?.villageName }}
          </div>
        </el-form-item>

        <el-form-item label="常住地址" prop="address">
          <el-input
            v-model="createForm.address"
            maxlength="200"
            placeholder="选填,便于村里联系"
          />
        </el-form-item>

        <!-- 密码不收集:固定初始密码,登记完线下告知本人 -->
        <el-form-item label="初始密码">
          <span class="num password-hint">123456</span>
          <span class="field-tip">固定初始密码,系统暂无自助改密入口,请登记后通过可靠渠道告知本人</span>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="submitCreate">
          提交备案
        </el-button>
      </template>
    </el-dialog>

    <!-- ── 审核弹窗 ──────────────────────────────────────── -->
    <el-dialog
      v-model="auditVisible"
      :title="auditForm.approved ? '确认通过备案' : '驳回备案'"
      width="520px"
    >
      <el-alert
        :type="auditForm.approved ? 'success' : 'warning'"
        :closable="false"
        show-icon
        class="dialog-alert"
        :title="auditForm.approved
          ? '通过后,这个人的归属村组就定下来了,可以正常使用本村的服务'
          : '驳回后,他的归属村组仍然为空,需要登记人修改后重新提交'"
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
              : '请说明具体问题,登记人会收到这条原因'"
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
.outside-page {
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

.field-tip {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: 1.6;
}

.password-hint {
  font-weight: var(--font-weight-bold);
  letter-spacing: 1px;
  margin-right: var(--space-sm);
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
