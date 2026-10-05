<script setup>
/**
 * 我的钱包 —— 收益明细、提现申请、提现记录。
 *
 * 【为什么这一页必须给出流水,而不是只摆一个余额数字】
 * 一个查不出账的余额,用户不敢用;一个能一笔笔核对上的余额,才谈得上信任。
 * 所以收益明细不是装饰。
 *
 * 【但"余额 = 累计收入 − 累计提现"这个看起来最顺的公式是错的】
 * 平台上有两条出账路径:提现,以及买家自己付款。真正的恒等式是
 *   累计入账 − 累计付款 − 累计提现 = 余额
 * 只摆 totalIncome / totalWithdraw 两个数,买家会算不平(买家也付款),
 * 卖家也会算不平(totalIncome 里含押金退还,是买家那侧的收入)。
 * 与其硬凑一个简洁但经不起追问的公式,不如把四个数字分开摆着、
 * 每个都标清口径 —— 这也是后端 WalletVO 刻意不做恒等式的同一个理由。
 *
 * 【能提的只有余额,提不走别人的押金】
 * 押金由买家支付、租完原路退回买家,它从来没有进过卖家的余额,
 * 所以"把买家的押金提走"在这套账上不可能发生 —— 不是靠校验拦住的,
 * 而是结构上就没有这条路径。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Wallet, Money, TrendCharts } from '@element-plus/icons-vue'
import { getWalletInfo, getEarnings, applyWithdraw, getMyWithdraws } from '@/api/wallet'
import { useUserStore } from '@/stores/user'
import { PAY_TYPE } from '@/utils/orderDict'
import { ACCOUNT_TYPE, WITHDRAW_STATUS, PAY_DIRECTION } from '@/utils/interactDict'
import { formatDateTime, formatMoney } from '@/utils/format'

const userStore = useUserStore()

const loading = ref(true)
const wallet = ref(null)

const activeTab = ref('earnings')

// ── 收益明细 ──────────────────────────────────────────────────
const earnings = reactive({ rows: [], total: 0, pageNum: 1, pageSize: 10, loading: false })

// ── 提现记录 ──────────────────────────────────────────────────
const withdraws = reactive({ rows: [], total: 0, pageNum: 1, pageSize: 10, loading: false })

// ── 提现表单 ──────────────────────────────────────────────────
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref()

const form = reactive({
  amount: null,
  accountType: 1,
  accountNo: '',
  accountName: ''
})

const rules = {
  amount: [
    { required: true, message: '请输入提现金额', trigger: 'blur' },
    {
      validator: (rule, value, cb) => {
        if (value == null || value === '') return cb()
        const min = wallet.value?.minWithdrawAmount ?? 10
        if (Number(value) < min) return cb(new Error(`单笔最低提现 ${formatMoney(min)} 元`))
        if (Number(value) > Number(wallet.value?.balance ?? 0)) {
          return cb(new Error(`可提现金额为 ${formatMoney(wallet.value?.balance)} 元`))
        }
        cb()
      },
      trigger: 'blur'
    }
  ],
  accountType: [{ required: true, message: '请选择收款方式', trigger: 'change' }],
  accountNo: [{ required: true, message: '请填写收款账号', trigger: 'blur' }]
}

/** 当前收款方式对应的账号输入提示 */
function accountPlaceholder(type) {
  return ACCOUNT_TYPE[type]?.placeholder || ''
}

/** 全部可提现金额一键填入 —— 提现最常见的动作是"全提" */
const canWithdrawAll = computed(() => {
  const b = Number(wallet.value?.balance ?? 0)
  return b >= (wallet.value?.minWithdrawAmount ?? 10)
})

async function fetchWallet() {
  try {
    wallet.value = await getWalletInfo()
  } catch (e) {
    wallet.value = null
  } finally {
    loading.value = false
  }
}

async function fetchEarnings() {
  earnings.loading = true
  try {
    const data = await getEarnings({ pageNum: earnings.pageNum, pageSize: earnings.pageSize })
    earnings.rows = data.rows || []
    earnings.total = data.total || 0
  } catch (e) {
    earnings.rows = []
  } finally {
    earnings.loading = false
  }
}

async function fetchWithdraws() {
  withdraws.loading = true
  try {
    const data = await getMyWithdraws({ pageNum: withdraws.pageNum, pageSize: withdraws.pageSize })
    withdraws.rows = data.rows || []
    withdraws.total = data.total || 0
  } catch (e) {
    withdraws.rows = []
  } finally {
    withdraws.loading = false
  }
}

/**
 * 打开提现弹窗。
 *
 * 户名默认填实名姓名 —— 这是最常见的情况(收款户名就是本人),
 * 但**不锁死**:帮家人代收、对公账户都是真实存在的场景,
 * 填死反而会逼用户改实名。后端也不校验这个字段。
 */
function openDialog() {
  form.amount = null
  form.accountType = 1
  form.accountNo = ''
  form.accountName = userStore.userInfo?.realName || ''
  dialogVisible.value = true
}

function fillAll() {
  form.amount = Number(wallet.value?.balance ?? 0)
}

async function submitWithdraw() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    await applyWithdraw({
      amount: Number(form.amount),
      accountType: form.accountType,
      accountNo: form.accountNo.trim(),
      accountName: form.accountName.trim() || undefined
    })
    ElMessage.success('提现申请已提交,管理员审核后打款')
    dialogVisible.value = false
    await fetchWallet()
    withdraws.pageNum = 1
    fetchWithdraws()
  } catch (e) {
    // 拦截器已提示业务错误(余额不足 / 低于起提额等)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  fetchWallet()
  fetchEarnings()
  fetchWithdraws()
})
</script>

<template>
  <div class="page-container my-wallet">
    <div v-if="loading" class="card"><el-skeleton :rows="6" animated /></div>

    <template v-else-if="wallet">
      <!-- ── 余额总览 ────────────────────────────────────── -->
      <section class="card overview">
        <div class="balance-block">
          <div class="balance-label">
            <el-icon><Wallet /></el-icon> 可提现余额(元)
          </div>
          <div class="balance-num">{{ formatMoney(wallet.balance) }}</div>
          <div v-if="Number(wallet.pendingWithdraw) > 0" class="balance-sub">
            另有 <b>{{ formatMoney(wallet.pendingWithdraw) }}</b> 元在提现审核中
          </div>
          <el-button
            type="primary"
            size="large"
            class="withdraw-btn"
            :disabled="!canWithdrawAll"
            @click="openDialog"
          >
            {{ canWithdrawAll ? '申请提现' : `余额不足 ${formatMoney(wallet.minWithdrawAmount)} 元` }}
          </el-button>
        </div>

        <div class="stat-grid">
          <div class="stat">
            <div class="stat-icon income"><el-icon><TrendCharts /></el-icon></div>
            <div>
              <div class="stat-label">累计入账</div>
              <div class="stat-value success">+{{ formatMoney(wallet.totalIncome) }}</div>
              <div class="stat-sub">货款结算 + 押金退还,只增不减</div>
            </div>
          </div>

          <div class="stat">
            <div class="stat-icon payout"><el-icon><Money /></el-icon></div>
            <div>
              <div class="stat-label">累计提现</div>
              <div class="stat-value">{{ formatMoney(wallet.totalWithdraw) }}</div>
              <div class="stat-sub">含待审核,已驳回的不算</div>
            </div>
          </div>

          <div class="stat">
            <div class="stat-icon pending"><el-icon><Money /></el-icon></div>
            <div>
              <div class="stat-label">审核中</div>
              <div class="stat-value warning">{{ wallet.pendingWithdraw }}</div>
              <div class="stat-sub">已被占用,暂不可提</div>
            </div>
          </div>
        </div>

        <p class="ledger-note">
          三个数字的口径:累计入账含货款结算与押金退还(只增不减);
          累计提现含待审核的,<b>已驳回的不算</b>(钱没出去过);
          「审核中」是累计提现里还没打款的那部分。
          下方流水与提现记录可以逐笔核对 ——
          <b>押金不在这里</b>,它由买家支付、租完原路退回买家。
        </p>
      </section>

      <!-- ── 明细 ────────────────────────────────────────── -->
      <section class="card detail-card">
        <el-tabs v-model="activeTab">
          <el-tab-pane label="收益明细" name="earnings">
            <el-table :data="earnings.rows" v-loading="earnings.loading" stripe>
              <el-table-column label="流水号" width="180">
                <template #default="{ row }">
                  <span class="num-text">{{ row.payNo }}</span>
                  <div class="text-xs text-secondary">{{ formatDateTime(row.payTime || row.createTime) }}</div>
                </template>
              </el-table-column>

              <el-table-column label="类型" width="140">
                <template #default="{ row }">
                  <el-tag
                    size="small"
                    effect="plain"
                    :type="PAY_DIRECTION[row.direction]?.type"
                  >
                    {{ PAY_DIRECTION[row.direction]?.label }} ·
                    {{ row.payTypeName || PAY_TYPE[row.payType] }}
                  </el-tag>
                </template>
              </el-table-column>

              <el-table-column label="关联订单 / 资源" min-width="220">
                <template #default="{ row }">
                  <div class="num-text">{{ row.orderNo || '—' }}</div>
                  <div class="text-xs text-secondary ellipsis">{{ row.resourceName }}</div>
                </template>
              </el-table-column>

              <el-table-column label="金额" width="130" align="right">
                <template #default="{ row }">
                  <span :class="['amount', row.direction === 1 ? 'is-income' : 'is-payout']">
                    {{ row.direction === 1 ? '+' : '−' }}{{ formatMoney(row.amount) }}
                  </span>
                </template>
              </el-table-column>

              <el-table-column label="备注" min-width="150">
                <template #default="{ row }">
                  <span class="text-xs text-secondary">{{ row.remark || '—' }}</span>
                </template>
              </el-table-column>
            </el-table>

            <el-pagination
              v-if="earnings.total > earnings.pageSize"
              v-model:current-page="earnings.pageNum"
              :page-size="earnings.pageSize"
              :total="earnings.total"
              layout="total, prev, pager, next"
              class="pager"
              @current-change="fetchEarnings"
            />
            <el-empty
              v-if="!earnings.loading && !earnings.rows.length"
              description="还没有资金流水 —— 订单完成结算后会出现在这里"
              :image-size="80"
            />
          </el-tab-pane>

          <el-tab-pane label="提现记录" name="withdraws">
            <el-table :data="withdraws.rows" v-loading="withdraws.loading" stripe>
              <el-table-column label="提现单号" width="180">
                <template #default="{ row }">
                  <span class="num-text">{{ row.withdrawNo }}</span>
                  <div class="text-xs text-secondary">{{ formatDateTime(row.applyTime) }}</div>
                </template>
              </el-table-column>

              <el-table-column label="金额" width="120" align="right">
                <template #default="{ row }">
                  <b class="amount">¥{{ formatMoney(row.amount) }}</b>
                </template>
              </el-table-column>

              <el-table-column label="收款账户" min-width="180">
                <template #default="{ row }">
                  <el-tag size="small" effect="plain">
                    {{ row.accountTypeName || ACCOUNT_TYPE[row.accountType]?.label }}
                  </el-tag>
                  <span class="text-xs text-secondary">{{ row.accountNo }}</span>
                </template>
              </el-table-column>

              <el-table-column label="状态" width="110" align="center">
                <template #default="{ row }">
                  <el-tag :type="WITHDRAW_STATUS[row.status]?.type" size="small" effect="dark">
                    {{ WITHDRAW_STATUS[row.status]?.label }}
                  </el-tag>
                </template>
              </el-table-column>

              <el-table-column label="审核结果" min-width="220">
                <template #default="{ row }">
                  <template v-if="row.status !== 0">
                    <div class="text-xs text-secondary">
                      {{ row.auditUserName }} · {{ formatDateTime(row.auditTime) }}
                    </div>
                    <div class="remark">{{ row.auditRemark || '—' }}</div>
                    <div v-if="row.status === 1" class="text-xs text-secondary">
                      打款完成于 {{ formatDateTime(row.finishTime) }}
                    </div>
                  </template>
                  <span v-else class="text-xs text-secondary">等待管理员审核</span>
                </template>
              </el-table-column>
            </el-table>

            <el-pagination
              v-if="withdraws.total > withdraws.pageSize"
              v-model:current-page="withdraws.pageNum"
              :page-size="withdraws.pageSize"
              :total="withdraws.total"
              layout="total, prev, pager, next"
              class="pager"
              @current-change="fetchWithdraws"
            />
            <el-empty
              v-if="!withdraws.loading && !withdraws.rows.length"
              description="还没有提现记录"
              :image-size="80"
            />
          </el-tab-pane>
        </el-tabs>
      </section>
    </template>

    <el-empty v-else description="钱包信息加载失败,请刷新重试" />

    <!-- ── 提现申请 ────────────────────────────────────────── -->
    <el-dialog v-model="dialogVisible" title="申请提现" width="480px">
      <el-alert type="info" :closable="false" show-icon class="dialog-tip">
        <template #title>
          提交后金额会立即从余额扣除并进入审核,管理员打款或驳回。
          驳回的话金额会退回余额。
        </template>
      </el-alert>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="提现金额" prop="amount">
          <el-input-number
            v-model="form.amount"
            :min="0"
            :precision="2"
            :step="100"
            style="width: 180px"
            placeholder="0.00"
          />
          <el-button link type="primary" class="fill-all" @click="fillAll">
            全部提现({{ formatMoney(wallet?.balance) }})
          </el-button>
          <div class="text-xs text-secondary">
            单笔最低 {{ formatMoney(wallet?.minWithdrawAmount) }} 元,当前可提 {{ formatMoney(wallet?.balance) }} 元
          </div>
        </el-form-item>

        <el-form-item label="收款方式" prop="accountType">
          <el-radio-group v-model="form.accountType">
            <el-radio v-for="(v, k) in ACCOUNT_TYPE" :key="k" :value="Number(k)" border>
              {{ v.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="收款账号" prop="accountNo">
          <el-input v-model="form.accountNo" :placeholder="accountPlaceholder(form.accountType)" clearable />
        </el-form-item>

        <el-form-item label="收款户名">
          <el-input v-model="form.accountName" placeholder="选填,需与实名信息一致" clearable />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitWithdraw">
          提交申请
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.my-wallet {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}

/* ── 余额总览 ─────────────────────────────────────────────── */
.overview {
  padding: var(--space-xl);
}

.balance-block {
  padding-bottom: var(--space-lg);
  border-bottom: 1px solid var(--color-border-light);
}

.balance-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.balance-num {
  margin-top: var(--space-sm);
  font-family: var(--font-family-number);
  font-size: 44px;
  font-weight: var(--font-weight-bold);
  line-height: 1.1;
  letter-spacing: -0.02em;
  color: var(--color-text-primary);
}

.balance-sub {
  margin-top: var(--space-xs);
  font-size: var(--font-size-sm);
  color: var(--color-warning);
}

.withdraw-btn {
  margin-top: var(--space-md);
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-lg);
  margin-top: var(--space-lg);
}

.stat {
  display: flex;
  align-items: flex-start;
  gap: var(--space-sm);
}

.stat-icon {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-md);
}

.stat-icon.income {
  background: var(--color-success-light);
  color: var(--color-success);
}

.stat-icon.payout {
  background: var(--color-primary-light);
  color: var(--color-primary);
}

.stat-icon.pending {
  background: var(--color-warning-light);
  color: var(--color-warning);
}

.stat-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.stat-value {
  font-family: var(--font-family-number);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
}

.stat-value.success {
  color: var(--color-success);
}

.stat-value.warning {
  color: var(--color-warning);
}

.stat-sub {
  font-size: var(--font-size-xs);
  color: var(--color-text-placeholder);
}

.ledger-note {
  margin-top: var(--space-lg);
  padding: var(--space-sm) var(--space-md);
  font-size: var(--font-size-xs);
  line-height: 1.7;
  color: var(--color-text-secondary);
  background: var(--color-bg-page);
  border-radius: var(--radius-sm);
}

/* ── 明细 ─────────────────────────────────────────────────── */
.detail-card {
  padding: var(--space-md) var(--space-lg);
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

.amount {
  font-family: var(--font-family-number);
  font-weight: var(--font-weight-bold);
}

.amount.is-income {
  color: var(--color-success);
}

.amount.is-payout {
  color: var(--color-danger);
}

.remark {
  font-size: var(--font-size-xs);
  color: var(--color-text-regular);
}

.pager {
  margin-top: var(--space-md);
  justify-content: flex-end;
}

/* ── 弹窗 ─────────────────────────────────────────────────── */
.dialog-tip {
  margin-bottom: var(--space-md);
}

.fill-all {
  margin-left: var(--space-sm);
}

@media (max-width: 900px) {
  .stat-grid {
    grid-template-columns: 1fr;
  }

  .balance-num {
    font-size: 34px;
  }
}
</style>
