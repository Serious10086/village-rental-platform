<script setup>
/**
 * 后台 —— 操作日志。
 *
 * 【这一页是审计,不是调试】
 * 它要回答的是"三个月前那条资源是谁下架的",所以每一行都带操作人快照
 * (用户名是操作那一刻的名字,改了手机号也不会变)和 IP。
 *
 * 【失败的记录和成功的一样重要】
 * 只记成功的话,"有人反复尝试越权删除"这件事在日志里完全看不到 ——
 * 而那些失败的尝试恰恰最该被看见。所以状态那一列有两档,
 * 失败的行默认用红色标签标出来,并且能展开看异常信息。
 *
 * 【这一页只读】
 * 没有编辑、没有删除,以后也不该有:能改的日志就不是审计。
 */
import { ref, reactive, onMounted } from 'vue'
import { Search, Refresh, View } from '@element-plus/icons-vue'
import { getOperationLogs, getLogModules } from '@/api/log'
import { formatDateTime } from '@/utils/format'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const modules = ref([])

const query = reactive({
  pageNum: 1,
  pageSize: 15,
  username: '',
  module: '',
  status: null,
  /** 日期区间;空数组表示不限 */
  dateRange: []
})

const statusOptions = [
  { value: 1, label: '成功' },
  { value: 0, label: '失败' }
]

async function fetchList() {
  loading.value = true
  try {
    const [start, end] = query.dateRange || []
    const data = await getOperationLogs({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      username: query.username || undefined,
      module: query.module || undefined,
      status: query.status ?? undefined,
      startDate: start || undefined,
      endDate: end || undefined
    })
    rows.value = data.rows || []
    total.value = data.total || 0
  } catch (e) {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function loadModules() {
  try {
    modules.value = (await getLogModules()) || []
  } catch (e) {
    modules.value = []
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.username = ''
  query.module = ''
  query.status = null
  query.dateRange = []
  handleSearch()
}

// ── 详情 ──────────────────────────────────────────────────────
const dialogVisible = ref(false)
const current = ref(null)

function openDetail(row) {
  current.value = row
  dialogVisible.value = true
}

/**
 * 参数美化显示。
 *
 * <p>存进去的是 JSON 字符串,直接摊在弹窗里是一长条。
 * 能解析就缩进着显示(可读),解析不了就原样显示(总比看不到强)。
 */
function prettyParams(text) {
  if (!text) {
    return '(无参数)'
  }
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch (e) {
    return text
  }
}

onMounted(() => {
  fetchList()
  loadModules()
})
</script>

<template>
  <div class="admin-log">
    <el-alert type="info" :closable="false" show-icon class="scope-alert">
      <template #title>
        记录后台的<b>写操作</b>(审核、删除、调分、发布、下架等),查询不记 ——
        列表接口一天被翻几百次,记下来只会把要查的那几条淹掉。
        <b>失败的尝试也会记</b>,而且一样能筛出来:那些恰恰最该被看到。
        <b>日志只读</b> —— 能改的日志就不是审计。
      </template>
    </el-alert>

    <div class="card filter-bar">
      <el-input
        v-model="query.username"
        placeholder="操作人"
        clearable
        style="width: 150px"
        @keyup.enter="handleSearch"
      />
      <el-select
        v-model="query.module"
        placeholder="模块"
        clearable
        style="width: 150px"
        @change="handleSearch"
      >
        <el-option v-for="m in modules" :key="m" :label="m" :value="m" />
      </el-select>
      <el-select
        v-model="query.status"
        placeholder="结果"
        clearable
        style="width: 110px"
        @change="handleSearch"
      >
        <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-date-picker
        v-model="query.dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        style="width: 260px"
        @change="handleSearch"
      />
      <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
      <el-button :icon="Refresh" @click="handleReset">重置</el-button>
    </div>

    <div class="card table-card">
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column label="时间" width="165">
          <template #default="{ row }">
            <span class="text-xs">{{ formatDateTime(row.createTime) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作人" width="120">
          <template #default="{ row }">
            <span>{{ row.username || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="模块" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.module || '—' }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" min-width="140">
          <template #default="{ row }">
            <span class="op-text">{{ row.operation }}</span>
          </template>
        </el-table-column>

        <el-table-column label="结果" width="90" align="center">
          <template #default="{ row }">
            <el-tag
              size="small"
              effect="dark"
              :type="row.status === 1 ? 'success' : 'danger'"
            >
              {{ row.status === 1 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="耗时" width="90" align="right">
          <template #default="{ row }">
            <span class="num text-xs">{{ row.costTime }} ms</span>
          </template>
        </el-table-column>

        <el-table-column label="IP" width="130">
          <template #default="{ row }">
            <span class="num text-xs">{{ row.ip || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :icon="View" @click="openDetail(row)">
              详情
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

    <!-- ── 详情弹窗 ──────────────────────────────────────── -->
    <el-dialog v-model="dialogVisible" title="操作详情" width="680px">
      <template v-if="current">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="操作人">{{ current.username || '—' }}</el-descriptions-item>
          <el-descriptions-item label="时间">
            {{ formatDateTime(current.createTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="模块">{{ current.module || '—' }}</el-descriptions-item>
          <el-descriptions-item label="操作">{{ current.operation }}</el-descriptions-item>
          <el-descriptions-item label="方法" :span="2">
            <span class="mono">{{ current.method || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="请求" :span="2">
            <span class="mono">{{ current.requestUrl || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="结果">
            <el-tag size="small" effect="dark" :type="current.status === 1 ? 'success' : 'danger'">
              {{ current.status === 1 ? '成功' : '失败' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="耗时 / IP">
            {{ current.costTime }} ms / {{ current.ip || '—' }}
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="current.status !== 1" class="block">
          <div class="block-label">异常信息</div>
          <div class="block-text is-error">{{ current.errorMsg || '(未记录异常信息)' }}</div>
        </div>

        <div class="block">
          <div class="block-label">请求参数</div>
          <pre class="block-text mono">{{ prettyParams(current.params) }}</pre>
        </div>
      </template>

      <template #footer>
        <el-button @click="dialogVisible = false">关闭</el-button>
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
  flex-wrap: wrap;
}

.table-card {
  padding: var(--space-md) var(--space-lg);
}

.op-text {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.pager {
  margin-top: var(--space-md);
  justify-content: flex-end;
}

.mono {
  font-family: var(--font-family-number);
  font-size: var(--font-size-xs);
}

.block {
  margin-top: var(--space-md);
}

.block-label {
  margin-bottom: 6px;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.block-text {
  margin: 0;
  padding: 10px 14px;
  max-height: 220px;
  overflow: auto;
  background: var(--color-info-light);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--color-text-regular);
}

.block-text.is-error {
  background: var(--color-danger-light);
  border-color: #fecaca;
  color: var(--color-danger);
}
</style>
