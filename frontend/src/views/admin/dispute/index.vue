<script setup>
/**
 * 后台 —— 纠纷调解。
 *
 * 调解要看的四样东西,这个抽屉一屏给全:
 *   ① 订单摘要(争议的是哪笔交易、金额多少、原状态是什么)
 *   ② 双方陈述与证据
 *   ③ **本单的聊天记录**(全系统唯一能读到别人私聊的入口)
 *   ④ 处理结论
 *
 * 【为什么聊天记录要放在这里,而不是让管理员去"私聊截图"】
 * 纠纷里的典型争议是"他到底有没有把我往平台外引""他答应过什么"——
 * 这两件事的证据都在私聊里。管理员看不到,"保留事实"就成了一句空话。
 * 但这条口子必须收窄:接口校验的是"管辖该订单所属村的管理员",
 * 交易双方本人反而调不了 —— 他自己的会话列表里本来就有这些内容,
 * 不需要这个后门。
 *
 * 数据级权限同样落在这里:列表不接受前端传的 villageId,
 * 服务端按登录用户的 dataScope 覆盖。改地址栏参数不会越权。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, ChatLineSquare, Picture } from '@element-plus/icons-vue'
import { getAdminDisputes, getAdminDisputeDetail, handleDispute } from '@/api/dispute'
import { getOrderMessages } from '@/api/chat'
import { useUserStore } from '@/stores/user'
import { DISPUTE_TYPE, DISPUTE_STATUS, DISPUTE_RESULT, UNRESOLVED_STATUSES } from '@/utils/interactDict'
import { ORDER_STATUS } from '@/utils/orderDict'
import { formatDateTime, formatMoney } from '@/utils/format'

const userStore = useUserStore()

const loading = ref(false)
const rows = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  disputeNo: '',
  disputeType: null,
  status: null
})

const statusOptions = Object.entries(DISPUTE_STATUS).map(([v, o]) => ({
  value: Number(v), label: o.label
}))
const typeOptions = Object.entries(DISPUTE_TYPE).map(([v, o]) => ({
  value: Number(v), label: o.label
}))

const scopeText = {
  1: '全部村组的纠纷',
  2: '本镇范围内的纠纷',
  3: `本村(${userStore.userInfo?.villageName || ''})的纠纷`,
  4: '与你本人相关的纠纷'
}[userStore.userInfo?.dataScope] || ''

// ── 调解抽屉 ──────────────────────────────────────────────────
const drawerVisible = ref(false)
const detail = ref(null)
const detailLoading = ref(false)

/** 聊天记录独立于详情加载:案情看一眼就够,聊天记录可能要翻很久 */
const chatLoading = ref(false)
const chatLoaded = ref(false)
const chatMessages = ref([])
/** el-collapse 的展开项。空数组 = 折叠 */
const chatActive = ref([])

const handleForm = reactive({
  // 默认选"已调解":调解是常态,关闭是例外。
  // 默认值选常见路径,能省掉一次点击,也不会诱导人随手点错
  status: 2,
  handleResult: ''
})
const submitting = ref(false)

async function fetchList() {
  loading.value = true
  try {
    const data = await getAdminDisputes({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      disputeNo: query.disputeNo || undefined,
      disputeType: query.disputeType ?? undefined,
      status: query.status ?? undefined
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
  query.disputeNo = ''
  query.disputeType = null
  query.status = null
  handleSearch()
}

async function openDrawer(row) {
  drawerVisible.value = true
  detailLoading.value = true
  detail.value = null
  // 换一个案件必须把上一个案件的聊天记录清掉 ——
  // 不清的话,先看 A 案再打开 B 案且网络慢,会看到 A 的聊天挂在 B 的案情下面
  chatActive.value = []
  chatLoaded.value = false
  chatMessages.value = []
  handleForm.status = 2
  handleForm.handleResult = ''
  try {
    detail.value = await getAdminDisputeDetail(row.id)
  } catch (e) {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

/** 证据图片在库里是逗号分隔的字符串(设计上不做一对多表) */
function evidenceList(str) {
  if (!str) return []
  return String(str).split(',').map((s) => s.trim()).filter(Boolean)
}

/**
 * 展开聊天记录时才去拉接口。
 *
 * 不跟详情一起拉,是因为这个接口读的是<b>别人的私聊</b> ——
 * 每开一个案件就无差别地把双方几个月的对话全读一遍,既无必要,
 * 也会让"管理员看过谁的私聊"这件事变得无从追溯。
 */
async function loadChat() {
  if (chatLoaded.value || !detail.value) return
  chatLoading.value = true
  try {
    const data = await getOrderMessages({ orderId: detail.value.orderId, pageNum: 1, pageSize: 200 })
    chatMessages.value = data.rows || []
    chatLoaded.value = true
  } catch (e) {
    chatMessages.value = []
  } finally {
    chatLoading.value = false
  }
}

function onChatToggle(val) {
  // val 是当前展开的 name 数组。空数组也是 truthy,
  // 所以不能写 if (val) —— 那样"收起"也会去拉一次聊天记录。
  if (Array.isArray(val) && val.includes('chat')) loadChat()
}

/** 消息气泡上标的是"申请人/被申请人",而不是"我/对方" —— 管理员不是当事人 */
function speakerName(msg) {
  if (!detail.value) return ''
  if (msg.fromUserId === detail.value.applicantId) return detail.value.applicantName
  if (msg.fromUserId === detail.value.respondentId) return detail.value.respondentName
  return `用户${msg.fromUserId}`
}

async function submitHandle() {
  if (!handleForm.handleResult.trim()) {
    ElMessage.warning('请填写处理结论')
    return
  }
  const result = DISPUTE_RESULT[handleForm.status]
  try {
    await ElMessageBox.confirm(
      `将以「${result.label}」结案:${result.desc}。结论会通知双方,且不可再修改。`,
      '确认结案',
      { confirmButtonText: '确认结案', cancelButtonText: '再想想', type: 'warning' }
    )
  } catch {
    return
  }

  submitting.value = true
  try {
    await handleDispute(detail.value.id, {
      status: handleForm.status,
      handleResult: handleForm.handleResult.trim()
    })
    ElMessage.success('已处理,双方已收到通知')
    drawerVisible.value = false
    fetchList()
  } catch (e) {
    // 拦截器已提示业务错误(比如订单状态刚刚变化)
  } finally {
    submitting.value = false
  }
}

onMounted(fetchList)
</script>

<template>
  <div class="admin-dispute">
    <el-alert type="info" :closable="false" show-icon class="scope-alert">
      <template #title>
        当前数据范围:<b>{{ scopeText }}</b>。
        待受理与处理中的案件自动排在前面 —— 这个队列就是管理员的任务列表,
        系统不再额外群发通知打扰所有管理员。
      </template>
    </el-alert>

    <div class="card filter-bar">
      <el-input
        v-model="query.disputeNo"
        placeholder="按纠纷编号搜索"
        clearable
        style="width: 200px"
        @keyup.enter="handleSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select v-model="query.status" placeholder="处理状态" clearable style="width: 140px">
        <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>

      <el-select v-model="query.disputeType" placeholder="纠纷类型" clearable style="width: 140px">
        <el-option v-for="t in typeOptions" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>

      <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
      <el-button :icon="Refresh" @click="handleReset">重置</el-button>

      <div class="spacer" />
      <span class="text-sm text-secondary">共 <b class="num">{{ total }}</b> 件</span>
    </div>

    <div class="card table-card">
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column label="纠纷编号" width="175">
          <template #default="{ row }">
            <a class="no" @click="openDrawer(row)">{{ row.disputeNo }}</a>
            <div class="text-xs text-secondary">{{ formatDateTime(row.createTime) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="DISPUTE_TYPE[row.disputeType]?.type">
              {{ DISPUTE_TYPE[row.disputeType]?.label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="争议订单" min-width="200">
          <template #default="{ row }">
            <div class="num-text">{{ row.orderNo }}</div>
            <div class="text-xs text-secondary ellipsis">{{ row.resourceName }}</div>
          </template>
        </el-table-column>

        <el-table-column label="申请人 → 被申请人" min-width="170">
          <template #default="{ row }">
            <div class="party-line">
              <span>{{ row.applicantName }}</span>
              <el-icon class="arrow"><Right /></el-icon>
              <span>{{ row.respondentName }}</span>
            </div>
            <div class="text-xs text-secondary">{{ row.villageName }}</div>
          </template>
        </el-table-column>

        <el-table-column label="诉求金额" width="110" align="right">
          <template #default="{ row }">
            <span v-if="row.applyAmount" class="amount">¥{{ formatMoney(row.applyAmount) }}</span>
            <span v-else class="text-secondary">—</span>
          </template>
        </el-table-column>

        <el-table-column label="订单状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="ORDER_STATUS[row.orderStatus]?.type" size="small" effect="dark">
              {{ ORDER_STATUS[row.orderStatus]?.label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="处理状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="DISPUTE_STATUS[row.status]?.type" size="small">
              {{ DISPUTE_STATUS[row.status]?.label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <!--
              按钮级权限:没有 dispute:handle 的角色看不到这个按钮。
              但**前端隐藏挡不住直接发 HTTP 请求** —— 接口上还有
              @RequiresPerm("dispute:handle") 独立校验一次。
            -->
            <el-button
              v-if="UNRESOLVED_STATUSES.includes(row.status)"
              v-hasPerm="'dispute:handle'"
              link
              type="primary"
              size="small"
              @click="openDrawer(row)"
            >
              受理调解
            </el-button>
            <el-button v-else link type="info" size="small" @click="openDrawer(row)">
              查看结论
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

    <!-- ── 调解抽屉 ─────────────────────────────────────────── -->
    <el-drawer v-model="drawerVisible" title="纠纷调解" size="620px">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <!-- 争议订单摘要:调解结论要落到具体一笔交易上 -->
          <div class="section-title">争议订单</div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="订单号">
              <span class="num-text">{{ detail.orderNo }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="订单类型">{{ detail.orderTypeName }}</el-descriptions-item>
            <el-descriptions-item label="资源" :span="2">{{ detail.resourceName }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.rentStartDate" label="租期" :span="2">
              {{ detail.rentStartDate }} ~ {{ detail.rentEndDate }}
            </el-descriptions-item>
            <el-descriptions-item label="订单金额">
              <b class="text-danger">¥{{ formatMoney(detail.payAmount) }}</b>
            </el-descriptions-item>
            <el-descriptions-item label="所属村组">{{ detail.villageName }}</el-descriptions-item>
            <el-descriptions-item label="纠纷前状态">
              {{ ORDER_STATUS[detail.orderStatusBefore]?.label }}
            </el-descriptions-item>
            <el-descriptions-item label="当前状态">
              <el-tag :type="ORDER_STATUS[detail.orderStatus]?.type" size="small" effect="dark">
                {{ ORDER_STATUS[detail.orderStatus]?.label }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>

          <!-- 双方:调解要找得到人,所以带手机号 -->
          <div class="section-title">纠纷双方</div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="申请人">
              {{ detail.applicantName }}
              <div class="text-xs text-secondary">{{ detail.applicantPhone }}</div>
            </el-descriptions-item>
            <el-descriptions-item label="被申请人">
              {{ detail.respondentName }}
              <div class="text-xs text-secondary">{{ detail.respondentPhone }}</div>
            </el-descriptions-item>
          </el-descriptions>

          <!-- 案情 -->
          <div class="section-title">纠纷陈述</div>
          <div class="box">
            <div class="dispute-meta">
              <el-tag size="small" effect="plain" :type="DISPUTE_TYPE[detail.disputeType]?.type">
                {{ DISPUTE_TYPE[detail.disputeType]?.label }}
              </el-tag>
              <span class="text-xs text-secondary">
                诉求金额 ¥{{ formatMoney(detail.applyAmount) }} · 提交于 {{ formatDateTime(detail.createTime) }}
              </span>
            </div>
            <p class="long-text">{{ detail.description }}</p>

            <div v-if="evidenceList(detail.evidenceImages).length" class="evidence">
              <el-image
                v-for="(img, i) in evidenceList(detail.evidenceImages)"
                :key="i"
                :src="img"
                :preview-src-list="evidenceList(detail.evidenceImages)"
                :initial-index="i"
                fit="cover"
                class="evidence-img"
              >
                <template #error>
                  <div class="evidence-fallback"><el-icon><Picture /></el-icon></div>
                </template>
              </el-image>
            </div>
            <p v-else class="text-xs text-secondary">申请人未上传证据图片</p>
          </div>

          <!--
            聊天记录:默认折叠。
            这是全系统唯一能读到别人私聊的入口,展开时才发请求 ——
            不是为了省流量,而是让"管理员读过谁的私聊"这件事有明确的动作痕迹。
          -->
          <el-collapse v-model="chatActive" class="chat-collapse" @change="onChatToggle">
            <el-collapse-item :name="'chat'">
              <template #title>
                <span class="chat-title">
                  <el-icon><ChatLineSquare /></el-icon>
                  调取本单聊天记录
                  <el-tag size="small" type="warning" effect="plain">含未打码原文</el-tag>
                </span>
              </template>

              <div v-loading="chatLoading" class="chat-body">
                <template v-if="chatLoaded && chatMessages.length">
                  <div
                    v-for="msg in chatMessages"
                    :key="msg.id"
                    class="chat-row"
                    :class="{ 'is-flagged': msg.isFiltered === 1 }"
                  >
                    <div class="chat-head">
                      <b>{{ speakerName(msg) }}</b>
                      <span class="text-xs text-secondary">{{ formatDateTime(msg.createTime) }}</span>
                      <!--
                        标记出"用户当时看到的内容和这里不一样"。
                        管理员如果不知道这一点,会以为当事人当着对方的面说了这句话
                      -->
                      <el-tag v-if="msg.isFiltered === 1" size="small" type="danger" effect="plain">
                        已被敏感词过滤,当事人看到的是打码版
                      </el-tag>
                    </div>
                    <div class="chat-text">{{ msg.content }}</div>
                  </div>
                </template>
                <el-empty
                  v-else-if="chatLoaded"
                  description="双方在这笔订单下没有聊天记录"
                  :image-size="60"
                />
              </div>
            </el-collapse-item>
          </el-collapse>

          <!-- 已决:结论只读 -->
          <template v-if="!UNRESOLVED_STATUSES.includes(detail.status)">
            <div class="section-title">处理结论</div>
            <div class="box">
              <div class="dispute-meta">
                <el-tag size="small" :type="DISPUTE_STATUS[detail.status]?.type">
                  {{ DISPUTE_STATUS[detail.status]?.label }}
                </el-tag>
                <span class="text-xs text-secondary">
                  {{ detail.handlerName }} · {{ formatDateTime(detail.handleTime) }}
                </span>
              </div>
              <p class="long-text">{{ detail.handleResult }}</p>
            </div>
          </template>

          <!-- 未决:管理员填结论 -->
          <template v-else>
            <div class="section-title">处理意见</div>
            <div class="box">
              <el-alert type="warning" :closable="false" show-icon class="tip">
                <template #title>
                  结案后订单会自动恢复到「{{ ORDER_STATUS[detail.orderStatusBefore]?.label }}」,
                  双方都会收到通知,结论<b>不可再修改</b>。要退款走「强制取消订单」,
                  要扣分走「信用分调整」—— 纠纷本身不动钱、不改信用分。
                </template>
              </el-alert>

              <el-radio-group v-model="handleForm.status" class="result-radio">
                <el-radio v-for="(v, k) in DISPUTE_RESULT" :key="k" :value="Number(k)" border>
                  {{ v.label }}
                  <span class="text-xs text-secondary">{{ v.desc }}</span>
                </el-radio>
              </el-radio-group>

              <el-input
                v-model="handleForm.handleResult"
                type="textarea"
                :rows="4"
                maxlength="1000"
                show-word-limit
                placeholder="例如:经核实为双方对「随机配件」的理解不一致,已协调卖方补送粮仓,双方认可"
              />
            </div>
          </template>
        </template>
      </div>

      <template v-if="detail && UNRESOLVED_STATUSES.includes(detail.status)" #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitHandle">
          提交结案
        </el-button>
      </template>
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

.no {
  font-family: var(--font-family-number);
  font-size: var(--font-size-sm);
  color: var(--color-primary);
  cursor: pointer;
}

.num-text {
  font-family: var(--font-family-number);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.party-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--font-size-sm);
}

.arrow {
  color: var(--color-text-placeholder);
}

.amount {
  font-family: var(--font-family-number);
  font-weight: var(--font-weight-bold);
  color: var(--color-danger);
}

.pager {
  margin-top: var(--space-md);
  justify-content: flex-end;
}

/* ── 抽屉内部 ─────────────────────────────────────────────── */
.section-title {
  margin: var(--space-lg) 0 var(--space-sm);
  padding-left: 8px;
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
  border-left: 3px solid var(--color-primary);
}

.section-title:first-child {
  margin-top: 0;
}

.box {
  padding: var(--space-md);
  background: var(--color-bg-page);
  border-radius: var(--radius-sm);
}

.dispute-meta {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-bottom: var(--space-sm);
}

.long-text {
  font-size: var(--font-size-sm);
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.evidence {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  margin-top: var(--space-md);
}

.evidence-img {
  width: 84px;
  height: 84px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
}

.evidence-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-info-light);
  color: var(--color-text-placeholder);
}

/* ── 聊天记录 ─────────────────────────────────────────────── */
.chat-collapse {
  margin-top: var(--space-lg);
  border-top: none;
}

.chat-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: var(--font-weight-medium);
}

.chat-body {
  max-height: 340px;
  overflow-y: auto;
}

.chat-row {
  padding: var(--space-sm) var(--space-md);
  margin-bottom: var(--space-sm);
  background: var(--color-bg-page);
  border-radius: var(--radius-sm);
  border-left: 3px solid transparent;
}

/* 被过滤过的消息:左边一道红杠,让它在长列表里一眼可见 */
.chat-row.is-flagged {
  border-left-color: var(--color-danger);
}

.chat-head {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-bottom: 4px;
  font-size: var(--font-size-sm);
}

.chat-text {
  font-size: var(--font-size-sm);
  line-height: 1.6;
  word-break: break-word;
}

/* ── 处理表单 ─────────────────────────────────────────────── */
.tip {
  margin-bottom: var(--space-md);
}

.result-radio {
  display: flex;
  gap: var(--space-sm);
  margin-bottom: var(--space-md);
}

.result-radio :deep(.el-radio) {
  margin-right: 0;
}
</style>
