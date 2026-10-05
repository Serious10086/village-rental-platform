<script setup>
/**
 * 订单详情 —— 时间轴 + 按状态联动的操作区。
 *
 * 【时间轴的数据从哪来】
 * 订单表上存了五个时刻(confirm/pay/deliver/return/finish),不是一张
 * "状态变更日志表"。因为订单的流转是固定几步、不可跳、不重复的,
 * 用列表达既省一张表,又能直接用 SQL 算出"平均接单时长"这类指标。
 * 所以时间轴在前端就能拼出来,不用再发一次请求。
 *
 * 【按钮为什么由后端给】
 * order.actions 是后端按状态机算好的动作码。前端不写
 * "如果 status 是 1 就显示支付按钮"这类判断 —— 那样后端加一个状态,
 * 前端就要跟着改,漏改就会出现"按钮点了报错"。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Loading } from '@element-plus/icons-vue'
import {
  getOrderDetail, confirmOrder, rejectOrder, payOrder,
  deliverOrder, receiveOrder, returnOrder, cancelOrder
} from '@/api/order'
import { getEvaluationsByOrder } from '@/api/evaluation'
import { getDisputesByOrder, applyDispute, cancelDispute } from '@/api/dispute'
import { uploadImage } from '@/api/resource'
import { useUserStore } from '@/stores/user'
import EvaluationDialog from '@/components/EvaluationDialog.vue'
import EvaluationList from '@/components/EvaluationList.vue'
import { formatDateTime, formatMoney } from '@/utils/format'
import { UNRESOLVED_STATUSES, DISPUTE_TYPE } from '@/utils/interactDict'
import {
  ORDER_STATUS, ORDER_TYPE, PAY_STATUS, MY_ROLE, ORDER_ACTION, buildTimeline
} from '@/utils/orderDict'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const order = ref(null)
const loading = ref(true)
const acting = ref(false)

const evaluations = ref([])
const evalDialogVisible = ref(false)

/**
 * 这笔订单的纠纷记录。
 *
 * 从后端取而不是从 order.actions 里读 —— 纠纷不是订单状态机的动作,
 * 它是一条独立的记录,可能有好几轮。actions 描述的是"订单下一步能做什么",
 * 而"我能不能再申请一次纠纷"取决于"有没有还没出结论的那一条",
 * 这是两回事。
 */
const disputes = ref([])
const disputeDialogVisible = ref(false)
const disputeSubmitting = ref(false)
const disputeUploading = ref(false)

const disputeForm = ref({
  disputeType: 1,
  description: '',
  applyAmount: null,
  evidenceImages: []
})
const disputeFormRef = ref()

const orderId = route.params.id

const timeline = computed(() => buildTimeline(order.value))
const isRent = computed(() => order.value?.orderType === 1)

/** 时间轴上的当前进度:已完成(4)全亮;取消/拒绝的主线全部置灰 */
const activeStep = computed(() => {
  const status = order.value?.orderStatus
  if (status === 4) return 99
  if (status === 5 || status === 7) return 0
  return ORDER_STATUS[status]?.step ?? 0
})

async function load() {
  loading.value = true
  try {
    // 订单、评价、纠纷三者互不依赖,并行取 —— 串行会让详情页多等两个来回。
    // 纠纷也要在这里取:操作区要据此决定显示「申请纠纷」还是「撤销纠纷」,
    // 等用户点开才发现状态不对就晚了
    const [detail, evals, disputeList] = await Promise.all([
      getOrderDetail(orderId),
      getEvaluationsByOrder(orderId),
      getDisputesByOrder(orderId).catch(() => [])
    ])
    order.value = detail
    evaluations.value = evals.rows || []
    disputes.value = disputeList || []
  } catch (e) {
    router.replace('/order/list')
  } finally {
    loading.value = false
  }
}

onMounted(load)

/**
 * 能否评价。
 *
 * 三个条件缺一不可:订单已完成、我是交易一方、我还没评过。
 * "我评过没有"不能靠后端下发的 actions 判断 —— 那组动作码描述的是
 * 订单状态流转,评价不属于状态流转,它是订单完成之后的独立动作。
 */
const canEvaluate = computed(() => {
  if (!order.value || order.value.orderStatus !== 4) return false
  if (!['buyer', 'seller'].includes(order.value.myRole)) return false
  const myId = userStore.userInfo?.userId
  return !evaluations.value.some((e) => e.fromUserId === myId)
})

// ── 纠纷与联系对方 ────────────────────────────────────────────

/** 可申请纠纷的订单状态:待交付(2)、使用中(3) —— 与后端 BizDisputeServiceImpl 的判定一致 */
const DISPUTABLE_ORDER_STATUS = [2, 3]

/** 我是不是这笔订单的交易一方 */
const isParty = computed(() => ['buyer', 'seller'].includes(order.value?.myRole))

/** 对方是谁 —— 买家看到的对方是卖家,反之亦然 */
const counterpartId = computed(() =>
  order.value?.myRole === 'buyer' ? order.value?.sellerId : order.value?.buyerId
)

/** 还没出结论的那条纠纷(有它就不能再申请,订单也还挂在"纠纷中") */
const openDispute = computed(
  () => disputes.value.find((d) => UNRESOLVED_STATUSES.includes(d.status)) || null
)

/** 已经出过结论的纠纷 —— 订单恢复后仍能回看结论 */
const decidedDisputes = computed(
  () => disputes.value.filter((d) => !UNRESOLVED_STATUSES.includes(d.status))
)

/**
 * 能不能申请纠纷。
 *
 * 三个条件:我是交易一方、订单在可争议区间、当前没有未决纠纷。
 * 第三条看起来多余(订单都"纠纷中"了,状态判断自然会挡下来),
 * 但**两条提示的分工不同**:状态判断给出的是"订单当前状态为「纠纷中」,
 * 已付款且未完成的订单才能申请纠纷" —— 用户看到这句完全不知道该怎么办;
 * 而"已有正在处理的纠纷"直接告诉他等调解。后端也是这个顺序,
 * 前端跟上,用户才不会在两处看到互相矛盾的说法。
 */
const canApplyDispute = computed(() => {
  if (!isParty.value) return false
  if (!DISPUTABLE_ORDER_STATUS.includes(order.value?.orderStatus)) return false
  return !openDispute.value
})

async function loadDisputes() {
  try {
    disputes.value = await getDisputesByOrder(orderId) || []
  } catch (e) {
    disputes.value = []
  }
}

function contactCounterpart() {
  router.push({ path: '/messages', query: { to: counterpartId.value, orderId } })
}

/** 回看已出结论的纠纷 —— 结论在「我的纠纷」里,订单页只给个入口,不重复渲染一遍 */
function goMyDisputes() {
  router.push('/my/dispute')
}

function openDisputeDialog() {
  disputeForm.value = {
    disputeType: 1,
    description: '',
    // 默认诉求金额留空而不是填订单金额:不少纠纷只是要求对方履约,不涉及钱,
    // 预填一个数字会诱导用户把它当成"我要退这么多"
    applyAmount: null,
    evidenceImages: []
  }
  disputeDialogVisible.value = true
}

const disputeRules = {
  disputeType: [{ required: true, message: '请选择纠纷类型', trigger: 'change' }],
  description: [
    { required: true, message: '请描述纠纷情况', trigger: 'blur' },
    { min: 10, message: '请至少写 10 个字,管理员要据此判断', trigger: 'blur' }
  ]
}

/** 上传前校验 —— 服务端也会校验,这一层是为了少一次无用请求 */
function beforeDisputeUpload(file) {
  if (!file.type.startsWith('image/')) {
    ElMessage.error('只能上传图片')
    return false
  }
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('单张图片不能超过 10MB')
    return false
  }
  return true
}

async function handleDisputeUpload(options) {
  disputeUploading.value = true
  try {
    const data = await uploadImage(options.file)
    disputeForm.value.evidenceImages.push(data.url)
    options.onSuccess?.(data)
  } catch (e) {
    options.onError?.(e)
  } finally {
    disputeUploading.value = false
  }
}

function removeEvidence(index) {
  disputeForm.value.evidenceImages.splice(index, 1)
}

async function submitDispute() {
  try {
    await disputeFormRef.value.validate()
  } catch {
    return
  }

  disputeSubmitting.value = true
  try {
    await applyDispute({
      orderId: Number(orderId),
      disputeType: disputeForm.value.disputeType,
      description: disputeForm.value.description.trim(),
      applyAmount: disputeForm.value.applyAmount ?? undefined,
      evidenceImages: disputeForm.value.evidenceImages.length
        ? disputeForm.value.evidenceImages
        : undefined
    })
    ElMessage.success('已提交,管理员会尽快调解')
    disputeDialogVisible.value = false
    await Promise.all([load(), loadDisputes()])
  } catch (e) {
    // 业务错误(订单状态不符 / 已有未决纠纷 / 描述命中敏感词)由拦截器提示,
    // 弹窗保持打开、内容不丢 —— 用户改一改就能重提
  } finally {
    disputeSubmitting.value = false
  }
}

async function handleCancelDispute() {
  try {
    await ElMessageBox.confirm(
      '撤销后订单会恢复到申请纠纷之前的状态,对方会收到通知。' +
      '管理员已经开始调解的话,撤销会被拒绝。',
      '撤销纠纷申请',
      { confirmButtonText: '确认撤销', cancelButtonText: '再想想', type: 'warning' }
    )
  } catch {
    return
  }

  try {
    await cancelDispute(openDispute.value.id)
    ElMessage.success('已撤销,订单已恢复')
    await Promise.all([load(), loadDisputes()])
  } catch (e) {
    // 业务错误已由拦截器提示
  }
}

async function runAction(action) {
  const o = order.value
  let reason = null

  if (action === 'reject' || action === 'cancel') {
    try {
      const { value } = await ElMessageBox.prompt(
        action === 'reject'
          ? '买家会收到你的拒绝理由,请说明原因'
          : '请说明取消原因(选填)',
        action === 'reject' ? '拒绝该订单' : '取消订单',
        {
          confirmButtonText: '确定',
          cancelButtonText: '再想想',
          inputValidator: (v) => (action === 'reject' && !v?.trim()) ? '拒绝时必须填写原因' : true,
          type: 'warning'
        }
      )
      reason = value
    } catch {
      return
    }
  } else {
    const confirmText = {
      confirm: '确认接下这笔订单吗?确认后买家即可付款。',
      pay: `将模拟支付 ¥${formatMoney(o.payAmount)}${Number(o.depositAmount) > 0 ? `,其中押金 ¥${formatMoney(o.depositAmount)} 在订单完成后退还` : ''}。`,
      deliver: '确认已将资源交付给买家?交付后订单进入"使用中"。',
      receive: '确认已收到货物?确认后订单完成,款项结算给卖家、双方信用分 +2。',
      return: '确认已归还资源?确认后订单完成,押金原路退还、双方信用分 +2。'
    }[action]
    if (confirmText) {
      try {
        await ElMessageBox.confirm(confirmText, '请确认', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        })
      } catch {
        return
      }
    }
  }

  acting.value = true
  try {
    if (action === 'confirm') await confirmOrder(o.id)
    else if (action === 'reject') await rejectOrder(o.id, reason)
    else if (action === 'pay') await payOrder(o.id, 1)
    else if (action === 'deliver') await deliverOrder(o.id)
    else if (action === 'receive') await receiveOrder(o.id)
    else if (action === 'return') await returnOrder(o.id)
    else if (action === 'cancel') await cancelOrder(o.id, reason)
    ElMessage.success('操作成功')
    await load()
  } catch (e) {
    // 拦截器已提示
  } finally {
    acting.value = false
  }
}
</script>

<template>
  <div class="page-container order-detail">
    <div v-if="loading" class="card block">
      <el-skeleton :rows="8" animated />
    </div>

    <template v-else-if="order">
      <!-- 顶部状态条:一眼看到当前处于哪一步 -->
      <section class="status-banner" :class="`status-${order.orderStatus}`">
        <div class="banner-left">
          <h1>{{ ORDER_STATUS[order.orderStatus]?.label }}</h1>
          <p>
            <template v-if="order.orderStatus === 0">已提交订单,等待卖家确认接单</template>
            <template v-else-if="order.orderStatus === 1">卖家已接单,请在方便时完成支付</template>
            <template v-else-if="order.orderStatus === 2">
              {{ isRent ? '款项已付,等待卖家交付资源' : '款项已付,等待你确认收货' }}
            </template>
            <template v-else-if="order.orderStatus === 3">资源使用中,用完后请确认归还</template>
            <template v-else-if="order.orderStatus === 4">订单已完结,押金已退还</template>
            <template v-else-if="order.orderStatus === 5">订单已取消</template>
            <template v-else-if="order.orderStatus === 7">卖家拒绝了这笔订单</template>
          </p>
        </div>
        <div class="banner-right">
          <span class="text-sm">应付总额</span>
          <strong class="banner-amount">¥{{ formatMoney(order.payAmount) }}</strong>
        </div>
      </section>

      <!-- 操作区 -->
      <section v-if="order.actions?.length || canEvaluate || isParty" class="card block action-bar">
        <el-button
          v-for="action in order.actions"
          :key="action"
          :type="ORDER_ACTION[action]?.type"
          :plain="ORDER_ACTION[action]?.plain"
          size="large"
          :loading="acting"
          @click="runAction(action)"
        >
          {{ ORDER_ACTION[action]?.label }}
        </el-button>

        <!--
          评价按钮独立于 actions 之外:actions 描述的是订单状态流转,
          而评价是订单完成之后的独立动作,不改变订单状态。
          把它混进状态机里会让"已完成"变成非终态,反而更乱。
        -->
        <el-button
          v-if="canEvaluate"
          type="warning"
          size="large"
          @click="evalDialogVisible = true"
        >
          去评价
        </el-button>

        <!--
          联系对方 / 纠纷 也独立于 actions 之外,理由同上:
          这两件事都不改变订单状态,不属于状态流转。

          纠纷按钮三选一,互斥关系是"有没有未决纠纷":
            能申请 → 申请;有未决 → 只能撤销;都没有但有旧结论 → 回看结论
          同时出现"申请"和"撤销"两个按钮是矛盾的 —— 撤销的前提正是
          "已经申请过了",所以这里用 v-if / v-else-if 而不是各自 v-if。
        -->
        <el-button v-if="isParty" size="large" @click="contactCounterpart">
          联系对方
        </el-button>

        <el-button
          v-if="canApplyDispute"
          type="danger"
          plain
          size="large"
          @click="openDisputeDialog"
        >
          申请纠纷
        </el-button>
        <el-button
          v-else-if="openDispute"
          type="danger"
          size="large"
          @click="handleCancelDispute"
        >
          撤销纠纷申请
        </el-button>
        <el-button
          v-else-if="decidedDisputes.length"
          size="large"
          @click="goMyDisputes"
        >
          查看纠纷结论
        </el-button>

        <span v-if="order.myRole === 'admin'" class="text-secondary text-sm">
          你以管理员身份查看该订单
        </span>
      </section>

      <!--
        纠纷状态提示。
        挂在"纠纷中"(6)的订单,操作区几乎是空的 —— 不解释一句,
        用户会以为是页面坏了,而不是"在等管理员调解"
      -->
      <el-alert
        v-if="openDispute"
        type="warning"
        :closable="false"
        show-icon
        class="card block dispute-alert"
      >
        <template #title>
          这笔订单正在纠纷调解中({{ openDispute.disputeNo }}),
          {{ openDispute.statusName }}。调解期间订单暂停流转,结论出来后自动恢复。
        </template>
        <template #default>
          <span class="text-sm">
            申请人是 {{ openDispute.applicantName }},被申请人是 {{ openDispute.respondentName }}。
            涉及退款或扣分的由管理员另行走流程 —— 纠纷本身不动钱、不改信用分。
          </span>
        </template>
      </el-alert>

      <div class="detail-grid">
        <div class="main-col">
          <!-- 资源信息 -->
          <section class="card block">
            <h2 class="block-title">资源信息</h2>
            <div class="res-row">
              <router-link :to="`/resource/${order.resourceId}`">
                <img :src="order.coverUrl" :alt="order.resourceName" class="res-cover" />
              </router-link>
              <div class="res-info">
                <router-link :to="`/resource/${order.resourceId}`" class="res-name">
                  {{ order.resourceName }}
                </router-link>
                <p class="text-secondary text-sm">
                  <el-icon><Location /></el-icon> {{ order.location }}
                </p>
                <p class="text-secondary text-sm">
                  单价 ¥{{ formatMoney(order.unitPrice) }}
                  <template v-if="isRent"> / {{ isRent ? '天' : '' }}</template>
                </p>
              </div>
            </div>
          </section>

          <!-- 时间轴 -->
          <section class="card block">
            <h2 class="block-title">订单流程</h2>
            <el-steps direction="vertical" :active="activeStep" class="timeline">
              <el-step
                v-for="(node, idx) in timeline"
                :key="node.key"
                :title="node.title"
                :status="idx + 1 <= activeStep ? 'success' : 'wait'"
              >
                <template #description>
                  <div class="step-desc">
                    <span>{{ node.desc }}</span>
                    <span v-if="node.time" class="step-time">{{ node.time }}</span>
                  </div>
                </template>
              </el-step>
            </el-steps>
          </section>

          <!-- 买家留言 / 取消原因 -->
          <section v-if="order.buyerRemark || order.cancelReason" class="card block">
            <h2 class="block-title">
              {{ order.cancelReason ? '取消原因' : '买家留言' }}
            </h2>
            <p class="remark-text">{{ order.cancelReason || order.buyerRemark }}</p>
          </section>

          <!-- 双方评价 -->
          <section v-if="evaluations.length" class="card block">
            <h2 class="block-title">双方评价</h2>
            <EvaluationList
              :evaluations="evaluations"
              empty-text="完成交易后可以互相评价"
              @refresh="load"
            />
          </section>
        </div>

        <aside class="side-col">
          <!-- 费用明细 -->
          <section class="card block">
            <h2 class="block-title">费用明细</h2>
            <div class="info-row">
              <span>单价</span><span>¥{{ formatMoney(order.unitPrice) }}</span>
            </div>
            <div v-if="isRent" class="info-row">
              <span>租期</span><span>{{ order.rentStartDate }} ~ {{ order.rentEndDate }}</span>
            </div>
            <div v-if="isRent" class="info-row">
              <span>天数</span><span>{{ order.rentDays }} 天</span>
            </div>
            <div class="info-row">
              <span>{{ isRent ? '租金' : '货款' }}</span><span>¥{{ formatMoney(order.totalAmount) }}</span>
            </div>
            <div v-if="Number(order.depositAmount) > 0" class="info-row">
              <span>押金</span><span>¥{{ formatMoney(order.depositAmount) }}</span>
            </div>
            <el-divider />
            <div class="info-row total">
              <span>应付总额</span><span class="text-danger">¥{{ formatMoney(order.payAmount) }}</span>
            </div>
            <div class="info-row">
              <span>支付状态</span>
              <el-tag size="small" :type="order.payStatus === 1 ? 'success' : 'info'" effect="plain">
                {{ PAY_STATUS[order.payStatus] }}
              </el-tag>
            </div>
          </section>

          <!-- 订单与交易双方 -->
          <section class="card block">
            <h2 class="block-title">订单信息</h2>
            <div class="info-row">
              <span>订单号</span><span class="num-text">{{ order.orderNo }}</span>
            </div>
            <div class="info-row">
              <span>类型</span><span>{{ ORDER_TYPE[order.orderType]?.label }}</span>
            </div>
            <div class="info-row">
              <span>我的身份</span><span>{{ MY_ROLE[order.myRole] }}</span>
            </div>
            <div class="info-row">
              <span>下单时间</span><span class="num-text">{{ formatDateTime(order.createTime) }}</span>
            </div>
            <el-divider />
            <div class="party">
              <div class="party-role">买家</div>
              <div class="party-info">
                <span>{{ order.buyerName }}</span>
                <span class="text-secondary text-sm">
                  {{ order.buyerPhone }} · 信用 {{ order.buyerCredit }}
                </span>
              </div>
            </div>
            <div class="party">
              <div class="party-role">卖家</div>
              <div class="party-info">
                <span>{{ order.sellerName }}</span>
                <span class="text-secondary text-sm">
                  {{ order.sellerPhone }} · 信用 {{ order.sellerCredit }}
                </span>
              </div>
            </div>
            <el-divider />
            <div class="info-row">
              <span>所属村组</span><span>{{ order.villageName }}</span>
            </div>
          </section>
        </aside>
      </div>
    </template>

    <EvaluationDialog
      v-model="evalDialogVisible"
      :order="order"
      @success="load"
    />

    <!-- ── 申请纠纷 ────────────────────────────────────────── -->
    <el-dialog v-model="disputeDialogVisible" title="申请纠纷" width="600px">
      <el-alert type="warning" :closable="false" show-icon class="dispute-tip">
        <template #title>
          提交后订单会挂起等待管理员调解,对方会收到通知。
          请如实描述 —— 管理员会把本单聊天记录一并调出来核对。
        </template>
      </el-alert>

      <el-form
        ref="disputeFormRef"
        :model="disputeForm"
        :rules="disputeRules"
        label-width="90px"
      >
        <el-form-item label="纠纷类型" prop="disputeType">
          <el-radio-group v-model="disputeForm.disputeType">
            <el-radio
              v-for="(v, k) in DISPUTE_TYPE"
              :key="k"
              :value="Number(k)"
              border
              size="small"
            >
              {{ v.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="诉求金额">
          <el-input-number
            v-model="disputeForm.applyAmount"
            :min="0"
            :max="Number(order?.payAmount) || undefined"
            :precision="2"
            placeholder="不涉及金额可留空"
            style="width: 200px"
          />
          <span class="text-xs text-secondary inline-hint">
            如要求退款或赔偿则填写,仅要求对方履约可留空。
            上限为本单实付 ¥{{ formatMoney(order?.payAmount) }}
          </span>
        </el-form-item>

        <el-form-item label="纠纷描述" prop="description">
          <el-input
            v-model="disputeForm.description"
            type="textarea"
            :rows="5"
            maxlength="1000"
            show-word-limit
            placeholder="请说明发生了什么、你的诉求是什么。例如:约定的交付日期已过三天,对方仍未交付,也无法联系上,要求退还已付租金。"
          />
        </el-form-item>

        <el-form-item label="证据图片">
          <div class="evidence-grid">
            <div
              v-for="(url, i) in disputeForm.evidenceImages"
              :key="i"
              class="evidence-item"
            >
              <el-image :src="url" fit="cover" class="evidence-thumb" />
              <el-icon class="evidence-remove" @click="removeEvidence(i)">
                <Plus style="transform: rotate(45deg)" />
              </el-icon>
            </div>

            <el-upload
              v-if="disputeForm.evidenceImages.length < 9"
              :show-file-list="false"
              :before-upload="beforeDisputeUpload"
              :http-request="handleDisputeUpload"
              accept="image/*"
            >
              <div class="evidence-trigger" :class="{ 'is-loading': disputeUploading }">
                <el-icon :size="20">
                  <Loading v-if="disputeUploading" class="is-spinning" />
                  <Plus v-else />
                </el-icon>
                <span class="text-xs">{{ disputeUploading ? '上传中…' : '添加' }}</span>
              </div>
            </el-upload>
          </div>
          <div class="text-xs text-secondary">最多 9 张,单张不超过 10MB</div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="disputeDialogVisible = false">取消</el-button>
        <el-button
          type="danger"
          :loading="disputeSubmitting"
          @click="submitDispute"
        >
          提交纠纷
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.order-detail {
  padding-top: var(--space-xl);
  padding-bottom: var(--space-2xl);
}

.block {
  padding: var(--space-lg);
  margin-bottom: var(--space-md);
}

.block-title {
  font-size: var(--font-size-md);
  margin-bottom: var(--space-md);
}

/* ── 顶部状态条 ───────────────────────────────────────────── */
.status-banner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-lg);
  border-radius: var(--radius-lg);
  margin-bottom: var(--space-md);
  background: var(--color-primary-light);
  border: 1px solid var(--color-primary-border);
}

.status-banner.status-4 {
  background: var(--color-success-light);
  border-color: #a7f3d0;
}

.status-banner.status-5,
.status-banner.status-7 {
  background: var(--color-info-light);
  border-color: var(--color-border);
}

.status-banner.status-1,
.status-banner.status-6 {
  background: var(--color-danger-light);
  border-color: #fecaca;
}

.banner-left h1 {
  font-size: var(--font-size-xl);
  margin-bottom: var(--space-xs);
}

.banner-left p {
  color: var(--color-text-regular);
  font-size: var(--font-size-sm);
}

.banner-right {
  text-align: right;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.banner-amount {
  font-size: var(--font-size-2xl);
  color: var(--color-danger);
  font-family: var(--font-family-number);
}

.action-bar {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: var(--space-lg);
  align-items: start;
}

/* ── 资源摘要 ─────────────────────────────────────────────── */
.res-row {
  display: flex;
  gap: var(--space-md);
}

.res-cover {
  width: 110px;
  height: 82px;
  object-fit: cover;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
}

.res-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.res-name {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.res-name:hover {
  color: var(--color-primary);
}

/* ── 时间轴 ───────────────────────────────────────────────── */
.timeline {
  padding-left: var(--space-xs);
}

.timeline :deep(.el-step__title) {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
}

.timeline :deep(.el-step__description) {
  padding-bottom: var(--space-md);
}

.step-desc {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.step-time {
  font-family: var(--font-family-number);
  font-size: var(--font-size-xs);
  color: var(--color-text-placeholder);
}

.remark-text {
  font-size: var(--font-size-sm);
  line-height: 1.7;
  color: var(--color-text-regular);
  white-space: pre-wrap;
}

/* ── 侧栏信息行 ───────────────────────────────────────────── */
.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-md);
  padding: 6px 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.info-row > span:last-child {
  color: var(--color-text-primary);
  text-align: right;
}

.info-row.total {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
}

.num-text {
  font-family: var(--font-family-number);
  font-size: var(--font-size-xs);
}

.party {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-sm) 0;
}

.party-role {
  width: 44px;
  flex-shrink: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  background: var(--color-info-light);
  border-radius: var(--radius-sm);
  text-align: center;
  padding: 2px 0;
}

.party-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: var(--font-size-sm);
}

/* ── 纠纷 ─────────────────────────────────────────────────── */
.dispute-alert {
  margin-top: var(--space-md);
}

.dispute-tip {
  margin-bottom: var(--space-md);
}

.inline-hint {
  margin-left: var(--space-sm);
  line-height: 1.6;
}

.evidence-grid {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.evidence-item {
  position: relative;
  width: 88px;
  height: 88px;
  border-radius: var(--radius-md);
  overflow: hidden;
  border: 1px solid var(--color-border);
}

.evidence-thumb {
  width: 100%;
  height: 100%;
}

.evidence-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 18px;
  height: 18px;
  border-radius: var(--radius-full);
  background: rgba(15, 23, 42, 0.6);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
}

.evidence-trigger {
  width: 88px;
  height: 88px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-md);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all var(--duration-fast);
}

.evidence-trigger:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.evidence-trigger.is-loading {
  pointer-events: none;
  opacity: 0.7;
}

.is-spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 900px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
