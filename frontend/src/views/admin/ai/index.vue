<script setup>
/**
 * 后台 —— AI 会话记录与降级统计。
 *
 * 【这一页回答的问题不是"AI 好不好用",而是"AI 不好的时候,是哪里不好"】
 * 降级率只说明有问题;原因分布才说明该去做什么:
 *   全是「超时」        → 查网络,或者把 read-timeout 调大
 *   全是「鉴权失败401」 → 密钥的事(失效 / 被撤销 / 压根没配)
 *   全是「余额不足402」 → 该充值了
 *   全是「限流429」     → 该降并发或者等一会儿
 *   而「提问包含违规词」根本不是故障 —— 那是内容安全在正常工作
 * 所以这一页把原因分布和降级率并排放在一起,而不是只给一个大数字。
 *
 * 【降级率的分母是"全部记录",这一点界面上必须写清楚】
 * 不然那个百分比没法解释。三种记录里,「失败」是"压根没调用"
 * (提问被内容安全拦下),把它也算进降级率会让"有人试图问违规内容"
 * 看起来像"AI 挂了",排查方向直接被带偏。它单独计数。
 *
 * 【转人工是什么,以及不是什么】
 * 系统里**没有在线的人工客服**。「标记跟进」不转接、不通知任何人,
 * 只是给管理员一个待办标记 —— 后台可以据此筛出"AI 没答好、
 * 需要有人跟一下"的记录。界面上这么说,是为了别让人以为点了它
 * 就会有客服接手。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh, View, BellFilled } from '@element-plus/icons-vue'
import { getAdminAiRecords, getAdminAiStat, markAiFollowUp } from '@/api/ai'
import { useUserStore } from '@/stores/user'
import { AI_STATUS_TYPE } from '@/utils/interactDict'
import { formatDateTime } from '@/utils/format'

const userStore = useUserStore()

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const stat = ref({})

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  status: null,
  onlyProblem: false
})

const statusOptions = [
  { value: 1, label: '成功' },
  { value: 2, label: '降级' },
  { value: 3, label: '失败' }
]

const scopeText = {
  1: '全部村组的对话',
  2: '本镇范围内的对话',
  3: `本村(${userStore.userInfo?.villageName || ''})的对话`,
  4: '你自己的对话'
}[userStore.userInfo?.dataScope] || ''

/** 原因分布里最长的那个,用来把条形按比例画出来 */
const maxReasonCount = computed(() =>
  Math.max(1, ...(stat.value.reasons || []).map((r) => r.count))
)

function barWidth(count) {
  return `${Math.max(4, (count / maxReasonCount.value) * 100)}%`
}

async function fetchList() {
  loading.value = true
  try {
    const params = {
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined,
      status: query.status ?? undefined,
      onlyProblem: query.onlyProblem || undefined
    }
    const [list, st] = await Promise.all([
      getAdminAiRecords(params),
      getAdminAiStat({ keyword: query.keyword || undefined })
    ])
    rows.value = list.rows || []
    total.value = list.total || 0
    stat.value = st || {}
  } catch (e) {
    rows.value = []
    total.value = 0
    stat.value = {}
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
  query.status = null
  query.onlyProblem = false
  handleSearch()
}

// ── 详情 ──────────────────────────────────────────────────────
const dialogVisible = ref(false)
const current = ref(null)

function openDetail(row) {
  current.value = row
  dialogVisible.value = true
}

// ── 标记跟进 ──────────────────────────────────────────────────
const following = ref(null)

async function handleFollowUp(row) {
  following.value = row.id
  try {
    await markAiFollowUp(row.id)
    ElMessage.success('已标记为需要人工跟进')
  } catch (e) {
    // 提示由 request.js 统一弹
  } finally {
    following.value = null
    await fetchList()
  }
}

onMounted(fetchList)
</script>

<template>
  <div class="admin-ai">
    <el-alert type="info" :closable="false" show-icon class="scope-alert">
      <template #title>
        当前数据范围:<b>{{ scopeText }}</b>。
        <b>降级率的分母是全部记录</b> —— 「降级」是调用了但没成功(超时/限流/欠费/密钥),
        <b>「失败」是压根没调用</b>(提问被内容安全拦下),它单独计数、不计入降级率。
        「标记跟进」<b>不会转接给谁</b>:系统里没有在线的人工客服,它只是一个待办标记。
      </template>
    </el-alert>

    <!-- ── 统计卡片 ──────────────────────────────────────── -->
    <section class="stat-grid">
      <article class="stat-card">
        <div class="stat-label">提问总数</div>
        <div class="stat-value num">{{ stat.total || 0 }}</div>
        <div class="stat-hint">统计范围内的全部对话</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">AI 回答成功</div>
        <div class="stat-value num">{{ stat.successCount || 0 }}</div>
        <div class="stat-hint">模型正常返回了回答</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">降级</div>
        <div class="stat-value num">{{ stat.degradedCount || 0 }}</div>
        <div class="stat-hint">调用了但没成功,给了本地兜底话术</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">失败(内容拦截)</div>
        <div class="stat-value num">{{ stat.failedCount || 0 }}</div>
        <div class="stat-hint">没调用,不算 AI 服务的问题</div>
      </article>
      <article class="stat-card is-accent">
        <div class="stat-label">降级率</div>
        <div class="stat-value num">{{ stat.degradeRate ?? 0 }}%</div>
        <div class="stat-hint">降级 ÷ 总数</div>
      </article>
      <article class="stat-card">
        <div class="stat-label">平均耗时</div>
        <div class="stat-value num">{{ stat.avgCostMs || 0 }}</div>
        <div class="stat-hint">毫秒,只统计成功的那些</div>
      </article>
    </section>

    <!-- ── 原因分布 ──────────────────────────────────────── -->
    <div class="card reason-card">
      <div class="card-head">
        <h3 class="card-title">降级原因分布</h3>
        <span class="card-sub">
          累计消耗 {{ (stat.totalTokens || 0).toLocaleString() }} tokens;最慢一次 {{ stat.maxCostMs || 0 }} ms
        </span>
      </div>
      <div v-if="stat.reasons?.length" class="reason-list">
        <div v-for="r in stat.reasons" :key="r.reason" class="reason-row">
          <span class="reason-name">{{ r.reason }}</span>
          <div class="reason-track">
            <div class="reason-bar" :style="{ width: barWidth(r.count) }" />
          </div>
          <span class="reason-count num">{{ r.count }}</span>
        </div>
      </div>
      <el-empty v-else :image-size="60" description="统计范围内还没有降级或失败的记录" />
    </div>

    <!-- ── 筛选栏 ────────────────────────────────────────── -->
    <div class="card filter-bar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索提问内容"
        clearable
        style="width: 220px"
        @keyup.enter="handleSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px">
        <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>

      <el-checkbox v-model="query.onlyProblem" label="只看降级和失败" @change="handleSearch" />

      <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
      <el-button :icon="Refresh" @click="handleReset">重置</el-button>
    </div>

    <!-- ── 表格 ──────────────────────────────────────────── -->
    <div class="card table-card">
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column label="提问" min-width="220">
          <template #default="{ row }">
            <div class="cell-text">{{ row.question }}</div>
          </template>
        </el-table-column>

        <el-table-column label="回答" min-width="240">
          <template #default="{ row }">
            <div class="cell-text is-muted">{{ row.answer }}</div>
          </template>
        </el-table-column>

        <el-table-column label="提问人" width="120">
          <template #default="{ row }">
            <div>{{ row.userName }}</div>
            <div class="text-xs text-secondary">{{ row.villageName || '未绑定村组' }}</div>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag size="small" effect="dark" :type="AI_STATUS_TYPE[row.status] || 'info'">
              {{ row.statusName }}
            </el-tag>
            <div v-if="row.degradeReason" class="text-xs reason-inline">
              {{ row.degradeReason }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="耗时 / tokens" width="120" align="right">
          <template #default="{ row }">
            <div class="num text-xs">{{ row.costTime }} ms</div>
            <div class="num text-xs text-secondary">
              {{ row.promptTokens }}+{{ row.completionTokens }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="时间" width="150">
          <template #default="{ row }">
            <span class="text-xs">{{ formatDateTime(row.createTime) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :icon="View" @click="openDetail(row)">
              详情
            </el-button>
            <!-- 权限码来自 sys_menu:ai:transfer -->
            <el-button
              v-if="row.isTransfer !== 1"
              v-hasPerm="'ai:transfer'"
              link
              type="warning"
              size="small"
              :loading="following === row.id"
              @click="handleFollowUp(row)"
            >
              跟进
            </el-button>
            <el-tag v-else size="small" type="warning" effect="plain">已标记</el-tag>
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
    <el-dialog v-model="dialogVisible" title="对话详情" width="720px">
      <template v-if="current">
        <el-descriptions :column="2" border size="small" class="detail-meta">
          <el-descriptions-item label="提问人">
            {{ current.userName }}({{ current.villageName || '未绑定村组' }})
          </el-descriptions-item>
          <el-descriptions-item label="时间">
            {{ formatDateTime(current.createTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" effect="dark" :type="AI_STATUS_TYPE[current.status] || 'info'">
              {{ current.statusName }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="降级原因">
            {{ current.degradeReason || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="模型">{{ current.model }}</el-descriptions-item>
          <el-descriptions-item label="耗时 / tokens">
            {{ current.costTime }} ms / {{ current.promptTokens }}+{{ current.completionTokens }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="qa-block">
          <div class="qa-label">提问</div>
          <div class="qa-text">{{ current.question }}</div>
        </div>
        <div class="qa-block">
          <div class="qa-label">
            回答
            <el-tag
              v-if="current.status !== 1"
              size="small"
              effect="plain"
              type="warning"
              class="qa-tag"
            >
              非 AI 生成
            </el-tag>
          </div>
          <div class="qa-text">{{ current.answer }}</div>
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

/* ── 统计卡片 ───────────────────────────────────────────────── */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: var(--space-md);
  margin-bottom: var(--space-md);
}

.stat-card {
  padding: var(--space-md) var(--space-lg);
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
}

.stat-card.is-accent {
  background: var(--color-primary-light);
  border-color: var(--color-primary-border);
}

.stat-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.stat-value {
  margin: 6px 0 4px;
  font-size: 26px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  line-height: 1.1;
}

.stat-card.is-accent .stat-value {
  color: var(--color-primary);
}

.stat-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: 1.5;
}

/* ── 原因分布 ───────────────────────────────────────────────── */
.reason-card {
  padding: var(--space-md) var(--space-lg);
  margin-bottom: var(--space-md);
}

.card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-md);
  margin-bottom: var(--space-md);
}

.card-title {
  margin: 0;
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.card-sub {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.reason-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.reason-row {
  display: grid;
  grid-template-columns: 130px 1fr 44px;
  align-items: center;
  gap: var(--space-sm);
}

.reason-name {
  font-size: var(--font-size-sm);
  color: var(--color-text-regular);
}

.reason-track {
  height: 10px;
  background: var(--color-info-light);
  border-radius: var(--radius-full);
  overflow: hidden;
}

.reason-bar {
  height: 100%;
  background: linear-gradient(90deg, var(--color-warning), #f59e0b);
  border-radius: var(--radius-full);
  transition: width var(--duration-normal) var(--ease-out);
}

.reason-count {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  text-align: right;
}

/* ── 筛选与表格 ─────────────────────────────────────────────── */
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

.cell-text {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: var(--font-size-sm);
  line-height: 1.6;
  color: var(--color-text-primary);
}

.cell-text.is-muted {
  color: var(--color-text-secondary);
}

.reason-inline {
  margin-top: 2px;
  color: var(--color-warning);
}

.pager {
  margin-top: var(--space-md);
  justify-content: flex-end;
}

/* ── 详情弹窗 ───────────────────────────────────────────────── */
.detail-meta {
  margin-bottom: var(--space-md);
}

.qa-block {
  margin-bottom: var(--space-md);
}

.qa-label {
  margin-bottom: 6px;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.qa-tag {
  margin-left: var(--space-sm);
}

.qa-text {
  padding: 10px 14px;
  background: var(--color-info-light);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-sm);
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--color-text-regular);
}

@media (max-width: 1280px) {
  .stat-grid {
    grid-template-columns: repeat(3, 1fr);
  }
}
</style>
