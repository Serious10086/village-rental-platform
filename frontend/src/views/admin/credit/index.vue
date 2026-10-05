<script setup>
/**
 * 后台 —— 信用管理。
 *
 * 对应需求里"村级管理员维护本村信用分规则"这一条。
 *
 * 【排序为什么是信用分升序】
 * 这是管理页面不是排行榜。管理员打开它要办的事是"看看谁快跌破门槛了",
 * 分数最低的排在最前面才有用。做成降序的话,第一屏永远是那几个
 * 本来就表现最好的人,而真正需要关注的排在最后一页。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { getAdminCreditList, getUserCreditRecords, adjustCredit } from '@/api/credit'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/format'

const userStore = useUserStore()

const loading = ref(false)
const rows = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  maxScore: null
})

const scopeText = {
  1: '全部村组的用户',
  2: '本镇范围内的用户',
  3: `本村(${userStore.userInfo?.villageName || ''})的用户`,
  4: '你本人'
}[userStore.userInfo?.dataScope] || ''

// ── 信用流水抽屉 ──────────────────────────────────────────────
const drawerVisible = ref(false)
const currentUser = ref(null)
const records = ref([])
const recordsLoading = ref(false)

// ── 调整分数 ──────────────────────────────────────────────────
const adjustVisible = ref(false)
const adjustForm = reactive({ userId: null, userName: '', delta: null, reason: '' })
const adjusting = ref(false)

async function fetchList() {
  loading.value = true
  try {
    const data = await getAdminCreditList({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined,
      maxScore: query.maxScore ?? undefined
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
  query.maxScore = null
  handleSearch()
}

async function openRecords(row) {
  currentUser.value = row
  drawerVisible.value = true
  recordsLoading.value = true
  try {
    const data = await getUserCreditRecords({ userId: row.userId, pageSize: 50 })
    records.value = data.rows || []
  } catch (e) {
    records.value = []
  } finally {
    recordsLoading.value = false
  }
}

function openAdjust(row) {
  adjustForm.userId = row.userId
  adjustForm.userName = row.realName
  adjustForm.delta = null
  adjustForm.reason = ''
  adjustVisible.value = true
}

async function submitAdjust() {
  if (!adjustForm.delta) {
    ElMessage.warning('请填写调整分值')
    return
  }
  if (!adjustForm.reason.trim()) {
    ElMessage.warning('请填写调整原因 —— 用户会看到这条说明')
    return
  }
  adjusting.value = true
  try {
    await adjustCredit({
      userId: adjustForm.userId,
      delta: adjustForm.delta,
      reason: adjustForm.reason.trim()
    })
    ElMessage.success('信用分已调整')
    adjustVisible.value = false
    fetchList()
  } catch (e) {
    // 拦截器已提示
  } finally {
    adjusting.value = false
  }
}

/** 信用分等级标签 —— 阈值跟后端 CreditServiceImpl 保持一致 */
function levelOf(score) {
  if (score >= 120) return { text: '优秀', type: 'success' }
  if (score >= 100) return { text: '良好', type: 'primary' }
  if (score >= 80) return { text: '一般', type: 'warning' }
  if (score >= 60) return { text: '较差', type: 'warning' }
  return { text: '受限', type: 'danger' }
}

function fmtPct(v) {
  return v == null ? '—' : `${v}%`
}

onMounted(fetchList)
</script>

<template>
  <div class="admin-credit">
    <el-alert type="info" :closable="false" show-icon class="scope-alert">
      <template #title>
        当前数据范围:<b>{{ scopeText }}</b>。
        列表按信用分升序 —— 最需要关注的排在最前面。
      </template>
    </el-alert>

    <div class="card filter-bar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索姓名或手机号"
        clearable
        style="width: 200px"
        @keyup.enter="handleSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select v-model="query.maxScore" placeholder="信用分不高于" clearable style="width: 160px">
        <el-option label="60 分以下(受限)" :value="59" />
        <el-option label="80 分以下" :value="79" />
        <el-option label="100 分以下" :value="99" />
      </el-select>

      <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
      <el-button :icon="Refresh" @click="handleReset">重置</el-button>

      <div class="spacer" />
      <span class="text-sm text-secondary">共 <b class="num">{{ total }}</b> 人</span>
    </div>

    <div class="card table-card">
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column label="用户" min-width="160">
          <template #default="{ row }">
            <div class="user-cell">
              <el-avatar :size="30">{{ (row.realName || '?').charAt(0) }}</el-avatar>
              <div>
                <div class="user-name">{{ row.realName || '未实名' }}</div>
                <div class="num-text">{{ row.phone }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="信用分" width="130" align="center">
          <template #default="{ row }">
            <div class="score-cell">
              <span class="score-num" :class="{ 'is-low': row.creditScore < 80 }">
                {{ row.creditScore }}
              </span>
              <el-tag :type="levelOf(row.creditScore).type" size="small" effect="plain">
                {{ levelOf(row.creditScore).text }}
              </el-tag>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="交易履约" width="130" align="center">
          <template #default="{ row }">
            <div>{{ fmtPct(row.fulfillRate) }}</div>
            <div class="num-text">
              完成 {{ row.finishOrderCount }}/{{ row.totalOrderCount }} 单
            </div>
          </template>
        </el-table-column>

        <el-table-column label="资源完好率" width="110" align="center">
          <template #default="{ row }">
            <span :class="{ 'text-secondary': row.intactRate == null }">
              {{ fmtPct(row.intactRate) }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="评价均分" width="110" align="center">
          <template #default="{ row }">
            <div>{{ row.avgScore == null ? '—' : Number(row.avgScore).toFixed(1) }}</div>
            <div class="num-text">{{ row.evalCount }} 条</div>
          </template>
        </el-table-column>

        <el-table-column label="纠纷" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.disputeOrderCount > 0" type="danger" size="small" effect="plain">
              {{ row.disputeOrderCount }}
            </el-tag>
            <span v-else class="text-secondary">—</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openRecords(row)">
              信用流水
            </el-button>
            <!-- 按钮级权限:没有 credit:adjust 的角色看不到这个按钮。
                 但前端隐藏挡不住直接发请求,接口上还有 @RequiresPerm 独立校验 -->
            <el-button
              v-hasPerm="'credit:adjust'"
              link
              type="warning"
              size="small"
              @click="openAdjust(row)"
            >
              调整分数
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

    <!-- 信用流水抽屉 -->
    <el-drawer v-model="drawerVisible" title="信用变动流水" size="560px">
      <div v-if="currentUser" class="drawer-head">
        <strong>{{ currentUser.realName }}</strong>
        <el-tag :type="levelOf(currentUser.creditScore).type" size="small">
          当前 {{ currentUser.creditScore }} 分
        </el-tag>
      </div>
      <div v-loading="recordsLoading">
        <el-empty v-if="!recordsLoading && !records.length" description="暂无信用变动记录"
          :image-size="70" />
        <div v-for="r in records" :key="r.id" class="record-item">
          <div>
            <div class="record-reason">{{ r.reason }}</div>
            <div class="text-xs text-secondary">
              {{ r.bizTypeName }}<span v-if="r.bizNo"> · {{ r.bizNo }}</span> · {{ formatDateTime(r.createTime) }}
            </div>
          </div>
          <div class="record-right">
            <div class="delta" :class="r.changeScore > 0 ? 'is-up' : 'is-down'">
              {{ r.changeScore > 0 ? '+' + r.changeScore : r.changeScore }}
            </div>
            <div class="text-xs text-secondary">{{ r.beforeScore }} → {{ r.afterScore }}</div>
          </div>
        </div>
      </div>
    </el-drawer>

    <!-- 调整分数 -->
    <el-dialog v-model="adjustVisible" title="调整信用分" width="440px">
      <el-form label-width="80px">
        <el-form-item label="用户">
          <span>{{ adjustForm.userName }}</span>
        </el-form-item>
        <el-form-item label="调整分值">
          <el-input-number v-model="adjustForm.delta" :min="-100" :max="100" :step="1" />
          <span class="text-xs text-secondary" style="margin-left: 8px">正数加分,负数扣分</span>
        </el-form-item>
        <el-form-item label="调整原因">
          <el-input
            v-model="adjustForm.reason"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="用户会看到这条说明,请写清楚依据"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" :loading="adjusting" @click="submitAdjust">确认调整</el-button>
      </template>
    </el-dialog>
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

.user-cell {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.user-name {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
}

.num-text {
  font-family: var(--font-family-number);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.score-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.score-num {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  font-family: var(--font-family-number);
}

.score-num.is-low {
  color: var(--color-danger);
}

.pager {
  margin-top: var(--space-md);
  justify-content: flex-end;
}

.drawer-head {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding-bottom: var(--space-md);
  margin-bottom: var(--space-sm);
  border-bottom: 1px solid var(--color-border-light);
}

.record-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-md) 0;
  border-bottom: 1px solid var(--color-border-light);
}

.record-reason {
  font-size: var(--font-size-sm);
  margin-bottom: 2px;
}

.record-right {
  text-align: right;
  flex-shrink: 0;
}

.delta {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
  font-family: var(--font-family-number);
}

.delta.is-up {
  color: var(--color-success);
}

.delta.is-down {
  color: var(--color-danger);
}
</style>
