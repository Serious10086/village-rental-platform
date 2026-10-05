<script setup>
/**
 * 我的信用 —— 信用分、三个比率、变动流水、收到的评价。
 *
 * 【这个页面就是需求原文那句"查看自身信用评分(基于交易履约、
 * 资源完好率、用户评价等)"的落地。】三个比率一一对应原文里的三个词,
 * 口径写在数据库视图 v_user_credit_summary 里,不是在这里现算的。
 *
 * 【为什么"暂无数据"和"0%"要分开显示】
 * 视图对没有数据的比率返回 NULL 而不是 0。一个刚注册的用户,
 * 履约率显示 0% 会让他以为自己做错了什么;显示"暂无数据"才是事实。
 * 这个区分只能在服务端做 —— 前端拿到 0 是分不清"真的是 0"还是"没有数据"的。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getMyCredit, getMyCreditRecords } from '@/api/credit'
import { getReceivedEvaluations, getSentEvaluations } from '@/api/evaluation'
import EvaluationList from '@/components/EvaluationList.vue'
import { formatDateTime } from '@/utils/format'

const loading = ref(true)
const credit = ref(null)

const activeTab = ref('records')

// ── 信用变动流水 ──────────────────────────────────────────────
const records = ref([])
const recordTotal = ref(0)
const recordsLoading = ref(false)
const recordQuery = ref({ pageNum: 1, pageSize: 10, bizType: null })

// ── 评价 ──────────────────────────────────────────────────────
const received = ref([])
const sent = ref([])
const evalLoading = ref(false)

const scoreRing = computed(() => {
  const s = credit.value?.creditScore ?? 100
  // 20~140 分映射到 0~100% 的进度环。低于 20 视作 0,高于 140 视作满
  return Math.max(0, Math.min(100, ((s - 20) / 120) * 100))
})

async function loadCredit() {
  loading.value = true
  try {
    credit.value = await getMyCredit()
  } finally {
    loading.value = false
  }
}

async function loadRecords() {
  recordsLoading.value = true
  try {
    const data = await getMyCreditRecords({
      pageNum: recordQuery.value.pageNum,
      pageSize: recordQuery.value.pageSize,
      bizType: recordQuery.value.bizType ?? undefined
    })
    records.value = data.rows || []
    recordTotal.value = data.total || 0
  } finally {
    recordsLoading.value = false
  }
}

async function loadEvaluations() {
  evalLoading.value = true
  try {
    const [recv, sentRes] = await Promise.all([
      getReceivedEvaluations({ pageSize: 20 }),
      getSentEvaluations({ pageSize: 20 })
    ])
    received.value = recv.rows || []
    sent.value = sentRes.rows || []
  } finally {
    evalLoading.value = false
  }
}

watch(activeTab, (tab) => {
  if (tab === 'records' && !records.value.length) loadRecords()
  if (tab === 'received' && !received.value.length) loadEvaluations()
  if (tab === 'sent' && !sent.value.length) loadEvaluations()
})

onMounted(() => {
  loadCredit()
  loadRecords()
})

/** 百分比展示:null 是"暂无数据",不是 0% */
function fmtPct(v) {
  return v == null ? '暂无数据' : `${v}%`
}

function fmtScore(v) {
  return v == null ? '暂无评分' : Number(v).toFixed(1)
}

/** 流水条目的颜色:加分为绿、扣分为红 */
function deltaClass(record) {
  if (record.changeScore > 0) return 'is-up'
  if (record.changeScore < 0) return 'is-down'
  return 'is-flat'
}

function deltaText(record) {
  return record.changeScore > 0 ? `+${record.changeScore}` : `${record.changeScore}`
}
</script>

<template>
  <div class="page-container my-credit">
    <div v-if="loading" class="card"><el-skeleton :rows="6" animated /></div>

    <template v-else-if="credit">
      <!-- 信用分总览 -->
      <section class="card overview">
        <div class="score-block">
          <el-progress
            type="dashboard"
            :percentage="scoreRing"
            :width="150"
            :stroke-width="10"
            :show-text="false"
            :color="credit.levelType === 'danger' ? '#dc2626'
              : credit.levelType === 'warning' ? '#d97706'
              : credit.levelType === 'success' ? '#059669' : '#2563eb'"
          />
          <div class="score-center">
            <div class="score-num">{{ credit.creditScore }}</div>
            <div class="text-xs text-secondary">信用分</div>
          </div>
        </div>

        <div class="level-block">
          <div class="level-head">
            <el-tag :type="credit.levelType" effect="dark" size="large">
              {{ credit.level }}
            </el-tag>
            <span v-if="credit.toNextLevel > 0" class="text-sm text-secondary">
              再得 {{ credit.toNextLevel }} 分升到下一等级
            </span>
          </div>
          <p class="level-desc">{{ credit.levelDesc }}</p>

          <div class="stat-row">
            <div class="stat">
              <div class="stat-label">交易履约率</div>
              <div class="stat-value" :class="{ 'is-empty': credit.fulfillRate == null }">
                {{ fmtPct(credit.fulfillRate) }}
              </div>
              <div class="stat-sub">
                完成 {{ credit.finishOrderCount }} / 共 {{ credit.totalOrderCount }} 单
              </div>
            </div>
            <div class="stat">
              <div class="stat-label">资源完好率</div>
              <div class="stat-value" :class="{ 'is-empty': credit.intactRate == null }">
                {{ fmtPct(credit.intactRate) }}
              </div>
              <div class="stat-sub">资源相符度 ≥ 4 星的比例</div>
            </div>
            <div class="stat">
              <div class="stat-label">用户评价均分</div>
              <div class="stat-value" :class="{ 'is-empty': credit.avgScore == null }">
                {{ fmtScore(credit.avgScore) }}
              </div>
              <div class="stat-sub">来自 {{ credit.evalCount }} 条评价</div>
            </div>
          </div>
        </div>
      </section>

      <!-- 明细 -->
      <section class="card detail-card">
        <el-tabs v-model="activeTab">
          <el-tab-pane label="信用变动" name="records">
            <div v-loading="recordsLoading" class="record-list">
              <el-empty
                v-if="!recordsLoading && !records.length"
                description="还没有信用变动记录"
                :image-size="80"
              />
              <div v-for="r in records" :key="r.id" class="record-item">
                <div class="record-left">
                  <div class="record-reason">{{ r.reason }}</div>
                  <div class="text-xs text-secondary">
                    {{ r.bizTypeName }}
                    <span v-if="r.bizNo"> · {{ r.bizNo }}</span>
                    · {{ formatDateTime(r.createTime) }}
                  </div>
                </div>
                <div class="record-right">
                  <div class="delta" :class="deltaClass(r)">{{ deltaText(r) }}</div>
                  <div class="text-xs text-secondary">{{ r.beforeScore }} → {{ r.afterScore }}</div>
                </div>
              </div>
            </div>
            <el-pagination
              v-if="recordTotal > recordQuery.pageSize"
              v-model:current-page="recordQuery.pageNum"
              :page-size="recordQuery.pageSize"
              :total="recordTotal"
              layout="prev, pager, next"
              class="pager"
              @current-change="loadRecords"
            />
          </el-tab-pane>

          <el-tab-pane name="received">
            <template #label>
              我收到的评价
              <span v-if="credit.evalCount" class="tab-count">{{ credit.evalCount }}</span>
            </template>
            <div v-loading="evalLoading">
              <EvaluationList
                :evaluations="received"
                empty-text="还没有人评价过你"
                @refresh="loadEvaluations"
              />
            </div>
          </el-tab-pane>

          <el-tab-pane label="我发出的评价" name="sent">
            <div v-loading="evalLoading">
              <EvaluationList
                :evaluations="sent"
                empty-text="你还没有评价过别人"
                @refresh="loadEvaluations"
              />
            </div>
          </el-tab-pane>
        </el-tabs>
      </section>
    </template>
  </div>
</template>

<style scoped>
.my-credit {
  padding-top: var(--space-xl);
  padding-bottom: var(--space-2xl);
}

.overview {
  display: flex;
  gap: var(--space-2xl);
  padding: var(--space-xl);
  align-items: center;
}

.score-block {
  position: relative;
  flex-shrink: 0;
}

.score-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
}

.score-num {
  font-size: 38px;
  font-weight: var(--font-weight-bold);
  font-family: var(--font-family-number);
  color: var(--color-text-primary);
  line-height: 1;
}

.level-block {
  flex: 1;
  min-width: 0;
}

.level-head {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  margin-bottom: var(--space-sm);
}

.level-desc {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  margin-bottom: var(--space-lg);
}

.stat-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-lg);
}

.stat {
  padding-left: var(--space-md);
  border-left: 2px solid var(--color-border-light);
}

.stat-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  margin-bottom: var(--space-xs);
}

.stat-value {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  font-family: var(--font-family-number);
  color: var(--color-text-primary);
}

.stat-value.is-empty {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-normal);
  color: var(--color-text-placeholder);
}

.stat-sub {
  font-size: var(--font-size-xs);
  color: var(--color-text-placeholder);
  margin-top: 2px;
}

.detail-card {
  margin-top: var(--space-md);
  padding: var(--space-md) var(--space-lg) var(--space-lg);
}

.tab-count {
  display: inline-block;
  margin-left: 4px;
  padding: 0 6px;
  border-radius: var(--radius-full);
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-size: var(--font-size-xs);
}

.record-list {
  min-height: 120px;
}

.record-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-md) 0;
  border-bottom: 1px solid var(--color-border-light);
}

.record-item:last-child {
  border-bottom: none;
}

.record-reason {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  margin-bottom: 2px;
}

.record-right {
  text-align: right;
  flex-shrink: 0;
}

.delta {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  font-family: var(--font-family-number);
}

.delta.is-up {
  color: var(--color-success);
}

.delta.is-down {
  color: var(--color-danger);
}

.delta.is-flat {
  color: var(--color-text-secondary);
}

.pager {
  margin-top: var(--space-md);
  justify-content: center;
}

@media (max-width: 900px) {
  .overview {
    flex-direction: column;
    align-items: stretch;
  }

  .stat-row {
    grid-template-columns: 1fr;
    gap: var(--space-md);
  }
}
</style>
