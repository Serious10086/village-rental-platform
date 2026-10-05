<script setup>
/**
 * 后台 —— 提现审核。
 *
 * 【这一页要审的是什么】
 * 用户在申请提现的那一刻钱就已经从余额里扣走了(行锁 + 数据库端自减),
 * 所以这里不是"批准扣款",而是<b>确认把钱打出去</b>:
 *   · 通过 → 状态转「已打款」,钱不再回到余额
 *   · 驳回 → 状态转「已驳回」,钱退回余额(退回和状态变更在同一事务内)
 * 这两种结果的差别,决定了"驳回必须填理由"—— 钱退了但用户不知道
 * 为什么被退,他只会再提一次,然后又被退一次。
 *
 * 【账户信息为什么显示完整号码】
 * 管理员要照着这个号码打款,打码了就没法用。而"我的提现记录"里
 * 显示的是自己的账号,也不需要打码。真正需要打码的只有站内信正文 ——
 * 那里是长期留存的文本,会被反复翻看。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { getAdminWithdraws, auditWithdraw } from '@/api/wallet'
import { useUserStore } from '@/stores/user'
import { ACCOUNT_TYPE, WITHDRAW_STATUS } from '@/utils/interactDict'
import { formatDateTime, formatMoney } from '@/utils/format'

const userStore = useUserStore()

const loading = ref(false)
const rows = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  withdrawNo: '',
  status: null
})

const statusOptions = Object.entries(WITHDRAW_STATUS).map(([v, o]) => ({
  value: Number(v), label: o.label
}))

const scopeText = {
  1: '全部村组的提现申请',
  2: '本镇范围内的提现申请',
  3: `本村(${userStore.userInfo?.villageName || ''})的提现申请`,
  4: '你本人的提现申请'
}[userStore.userInfo?.dataScope] || ''

const auditing = ref(null)

async function fetchList() {
  loading.value = true
  try {
    const data = await getAdminWithdraws({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      withdrawNo: query.withdrawNo || undefined,
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
  query.withdrawNo = ''
  query.status = null
  handleSearch()
}

/** 打款:钱在申请时已扣,这里只推进状态,不需要二次确认金额以外的东西 */
async function handlePass(row) {
  try {
    await ElMessageBox.confirm(
      `确认已向 ${row.accountName || row.userName} 的${
        ACCOUNT_TYPE[row.accountType]?.label || '账户'
      }(${row.accountNo})打款 ¥${formatMoney(row.amount)}?`,
      '确认打款',
      { confirmButtonText: '已打款', cancelButtonText: '再核对一下', type: 'warning' }
    )
  } catch {
    return
  }

  auditing.value = row.id
  try {
    await auditWithdraw(row.id, { pass: true, remark: '收款信息与实名一致,已打款' })
    ElMessage.success('已标记打款,钱不再退回余额')
    fetchList()
  } catch (e) {
    fetchList()
  } finally {
    auditing.value = null
  }
}

/**
 * 驳回。理由必填 —— 后端的 WithdrawAuditDTO 上也校验了这一点,
 * 前端这道只是让用户不用等一个 400 才知道要写理由。
 */
async function handleReject(row) {
  let reason
  try {
    const { value } = await ElMessageBox.prompt(
      `驳回后 ¥${formatMoney(row.amount)} 会<b>退回</b> ${row.userName} 的余额,并通知他原因。`,
      '驳回提现申请',
      {
        dangerouslyUseHTMLString: true,
        confirmButtonText: '确认驳回',
        cancelButtonText: '放弃',
        inputPlaceholder: '例如:收款账号与实名信息不一致,请核对后重新提交',
        inputValidator: (v) => (!v || !v.trim()) ? '必须填写驳回理由' : true,
        type: 'warning'
      }
    )
    reason = value.trim()
  } catch {
    return
  }

  auditing.value = row.id
  try {
    await auditWithdraw(row.id, { pass: false, remark: reason })
    ElMessage.success('已驳回,金额已退回余额')
    fetchList()
  } catch (e) {
    fetchList()
  } finally {
    auditing.value = null
  }
}

onMounted(fetchList)
</script>

<template>
  <div class="admin-withdraw">
    <el-alert type="info" :closable="false" show-icon class="scope-alert">
      <template #title>
        当前数据范围:<b>{{ scopeText }}</b>。
        用户<b>申请提现时金额就已从余额扣除</b>(数据库端自减 + 行锁,
        两人同时提现不会双双通过),所以这里通过只是确认打款,驳回才会把钱退回。
      </template>
    </el-alert>

    <div class="card filter-bar">
      <el-input
        v-model="query.withdrawNo"
        placeholder="按提现单号搜索"
        clearable
        style="width: 200px"
        @keyup.enter="handleSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select v-model="query.status" placeholder="审核状态" clearable style="width: 140px">
        <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>

      <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
      <el-button :icon="Refresh" @click="handleReset">重置</el-button>

      <div class="spacer" />
      <span class="text-sm text-secondary">共 <b class="num">{{ total }}</b> 笔</span>
    </div>

    <div class="card table-card">
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column label="提现单号" width="175">
          <template #default="{ row }">
            <div class="no">{{ row.withdrawNo }}</div>
            <div class="text-xs text-secondary">{{ formatDateTime(row.applyTime) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="申请人" min-width="140">
          <template #default="{ row }">
            <div>{{ row.userName }}</div>
            <div class="text-xs text-secondary">{{ row.userPhone }}</div>
          </template>
        </el-table-column>

        <el-table-column label="所属村组" width="110">
          <template #default="{ row }">
            <span class="text-sm">{{ row.villageName || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="提现金额" width="120" align="right">
          <template #default="{ row }">
            <b class="amount">¥{{ formatMoney(row.amount) }}</b>
          </template>
        </el-table-column>

        <el-table-column label="收款账户" min-width="200">
          <template #default="{ row }">
            <div class="account-line">
              <el-tag size="small" effect="plain">
                {{ row.accountTypeName || ACCOUNT_TYPE[row.accountType]?.label }}
              </el-tag>
              <span class="num-text">{{ row.accountNo }}</span>
            </div>
            <div class="text-xs text-secondary">户名 {{ row.accountName || row.userName }}</div>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="WITHDRAW_STATUS[row.status]?.type" size="small" effect="dark">
              {{ WITHDRAW_STATUS[row.status]?.label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="审核信息" min-width="180">
          <template #default="{ row }">
            <template v-if="row.status !== 0">
              <div class="text-xs text-secondary">
                {{ row.auditUserName }} · {{ formatDateTime(row.auditTime) }}
              </div>
              <div class="text-xs remark">{{ row.auditRemark }}</div>
            </template>
            <span v-else class="text-xs text-secondary">等待审核</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 0">
              <!--
                按钮级权限:没有 withdraw:audit 的角色看不到这两个按钮。
                但**前端隐藏挡不住直接发 HTTP 请求** ——
                接口上还有 @RequiresPerm("withdraw:audit") 独立校验一次。
              -->
              <el-button
                v-hasPerm="'withdraw:audit'"
                link
                type="primary"
                size="small"
                :loading="auditing === row.id"
                @click="handlePass(row)"
              >
                已打款
              </el-button>
              <el-button
                v-hasPerm="'withdraw:audit'"
                link
                type="danger"
                size="small"
                :loading="auditing === row.id"
                @click="handleReject(row)"
              >
                驳回
              </el-button>
            </template>
            <span v-else class="text-xs text-secondary">已处理</span>
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
  color: var(--color-text-secondary);
}

.amount {
  font-family: var(--font-family-number);
  font-size: var(--font-size-md);
  color: var(--color-danger);
}

.account-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.num-text {
  font-family: var(--font-family-number);
  font-size: var(--font-size-xs);
}

.remark {
  margin-top: 2px;
  color: var(--color-text-regular);
}

.pager {
  margin-top: var(--space-md);
  justify-content: flex-end;
}
</style>
