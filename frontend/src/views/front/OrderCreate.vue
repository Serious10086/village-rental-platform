<script setup>
/**
 * 下单确认页。
 *
 * 为什么单独一个页面而不是在详情页弹个框:
 * 租赁要选日期、要实时算钱、高风险资源还要勾安全确认 ——
 * 这些塞进弹窗里,用户看不到自己正在选的是什么资源,选错了也发现不了。
 * 独立页面能把"资源摘要 + 租期 + 费用明细"三块同时摆在眼前。
 *
 * 【费用全部在服务端再算一遍】
 * 这里的前端计算只为让用户心里有数,提交时只把 resourceId 和日期发给后端。
 * 单价、总额、押金一律不接受前端传入 —— 否则改一下请求体就能一块钱租走拖拉机。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getResourceDetail } from '@/api/resource'
import { createOrder } from '@/api/order'
import { PRICE_UNIT, DELIVERY_TYPE } from '@/utils/resourceDict'
import { formatMoney } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const resourceId = route.params.resourceId
const resource = ref(null)
const loading = ref(true)
const submitting = ref(false)

const dateRange = ref(null)
const remark = ref('')
const trainingConfirmed = ref(false)
const deliveryType = ref(null)

/** 可选的日期范围 —— 不能超出发布者设定的可租窗口,也不能选过去的日期 */
const disabledDate = (date) => {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  if (date < today) return true
  if (!resource.value) return false
  if (resource.value.rentStartDate && date < new Date(resource.value.rentStartDate)) return true
  if (resource.value.rentEndDate && date > new Date(resource.value.rentEndDate)) return true
  return false
}

const isRent = computed(() => resource.value?.tradeType === 1)

/**
 * 租期天数,含头含尾(和后端算法一致:9/1 租到 9/3 算 3 天)。
 *
 * 【踩过的坑】日期选择器配了 value-format="YYYY-MM-DD",所以
 * dateRange 里拿到的是<b>字符串</b>而不是 Date 对象。直接写
 * `(end - start) / 86400000` 会得到 NaN —— 而 NaN 参与比较时
 * 一律返回 false,于是"天数 <= 0 就禁用提交"这道校验被悄悄绕过,
 * 页面显示 ¥NaN 却还能提交。必须显式转成 Date 再算。
 */
const rentDays = computed(() => {
  if (!dateRange.value || dateRange.value.length !== 2) return 0
  const [s, e] = dateRange.value
  const start = new Date(`${s}T00:00:00`)
  const end = new Date(`${e}T00:00:00`)
  const days = Math.round((end - start) / 86400000) + 1
  return days > 0 ? days : 0
})

/** 计价单位不是"元/天"时,租多久都是同一个价 —— 乘天数反而算错 */
const byDay = computed(() => resource.value?.priceUnit === 1)

const totalAmount = computed(() => {
  if (!resource.value) return 0
  if (!isRent.value) return Number(resource.value.price)
  return byDay.value
    ? Number(resource.value.price) * rentDays.value
    : Number(resource.value.price)
})

const deposit = computed(() =>
  isRent.value ? Number(resource.value?.deposit || 0) : 0
)

const payAmount = computed(() => totalAmount.value + deposit.value)

const needTraining = computed(() => resource.value?.needTraining === 1)

const canSubmit = computed(() => {
  if (submitting.value) return false
  if (isRent.value && rentDays.value <= 0) return false
  if (needTraining.value && !trainingConfirmed.value) return false
  return true
})

onMounted(async () => {
  try {
    resource.value = await getResourceDetail(resourceId)
    deliveryType.value = resource.value.deliveryType
    if (resource.value.tradeType === 2) {
      remark.value = ''
    }
  } catch (e) {
    ElMessage.error('资源加载失败')
    router.replace('/resource')
  } finally {
    loading.value = false
  }
})

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const payload = {
      resourceId: Number(resourceId),
      buyerRemark: remark.value || null,
      trainingConfirmed: trainingConfirmed.value
    }
    if (isRent.value) {
      // dateRange 里已经是 value-format 后的 'YYYY-MM-DD' 字符串,直接发即可
      payload.rentStartDate = dateRange.value[0]
      payload.rentEndDate = dateRange.value[1]
    } else {
      payload.deliveryType = deliveryType.value
    }
    const orderId = await createOrder(payload)
    ElMessage.success('下单成功,等待发布者确认')
    router.replace(`/order/${orderId}`)
  } catch (e) {
    // 时段冲突这类业务错误由 axios 拦截器统一弹出来了,
    // 这里只需要把提交态恢复,让用户能改了日期重新提交
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page-container order-create">
    <div v-if="loading" class="loading-wrap">
      <el-skeleton :rows="6" animated />
    </div>

    <template v-else-if="resource">
      <header class="page-head">
        <h1>确认订单</h1>
        <p class="text-secondary">
          {{ isRent ? '核对租期与费用后提交,等待发布者确认' : '核对信息后提交购买申请' }}
        </p>
      </header>

      <div class="create-grid">
        <!-- 左:资源摘要 + 表单 -->
        <div class="main-col">
          <section class="card block">
            <h2 class="block-title">资源信息</h2>
            <div class="res-row">
              <img :src="resource.coverUrl" :alt="resource.resourceName" class="res-cover" />
              <div class="res-info">
                <router-link :to="`/resource/${resource.id}`" class="res-name">
                  {{ resource.resourceName }}
                </router-link>
                <div class="res-meta">
                  <el-tag :type="isRent ? 'primary' : 'warning'" size="small" effect="plain">
                    {{ isRent ? '出租' : '出售' }}
                  </el-tag>
                  <span class="text-secondary text-sm">{{ resource.categoryName }}</span>
                </div>
                <div class="res-price">
                  <span class="price-num">¥{{ formatMoney(resource.price) }}</span>
                  <span class="text-secondary text-sm">
                    {{ PRICE_UNIT[resource.priceUnit] || '' }}
                  </span>
                </div>
                <p class="text-secondary text-sm">
                  <el-icon><Location /></el-icon> {{ resource.location }}
                </p>
              </div>
            </div>
          </section>

          <section class="card block">
            <h2 class="block-title">{{ isRent ? '选择租期' : '交货方式' }}</h2>

            <template v-if="isRent">
              <el-date-picker
                v-model="dateRange"
                type="daterange"
                range-separator="至"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                :disabled-date="disabledDate"
                value-format="YYYY-MM-DD"
                class="date-picker"
              />
              <p class="hint">
                该资源可租时段:{{ resource.rentStartDate }} 至 {{ resource.rentEndDate }}
                <template v-if="rentDays > 0">
                  ,本次共 <b>{{ rentDays }}</b> 天
                </template>
              </p>
              <p class="hint">
                <el-icon><InfoFilled /></el-icon>
                同一时段只能有一笔有效订单。提交时系统会检测档期是否已被占用。
              </p>
            </template>

            <template v-else>
              <el-radio-group v-model="deliveryType">
                <el-radio :value="1">自提</el-radio>
                <el-radio :value="2">与卖家协商</el-radio>
              </el-radio-group>
            </template>
          </section>

          <!-- 高风险资源的安全确认 —— 由资源分类的 risk_level 驱动,
               发布资源时自动继承,不用管理员逐条判断 -->
          <section v-if="needTraining" class="card block training-block">
            <h2 class="block-title">
              <el-icon class="warn-icon"><WarningFilled /></el-icon>
              安全须知(高风险资源)
            </h2>
            <p class="training-content">{{ resource.trainingContent }}</p>
            <el-checkbox v-model="trainingConfirmed" class="training-check">
              我已阅读并理解上述安全须知,承诺按规定操作
            </el-checkbox>
          </section>

          <section class="card block">
            <h2 class="block-title">买家留言</h2>
            <el-input
              v-model="remark"
              type="textarea"
              :rows="3"
              maxlength="500"
              show-word-limit
              placeholder="有什么需要提前和发布者说明的,写在这里(选填)"
            />
          </section>
        </div>

        <!-- 右:费用明细 + 提交。用 sticky 让它始终留在视野内 -->
        <aside class="side-col">
          <section class="card block summary">
            <h2 class="block-title">费用明细</h2>

            <div class="fee-row">
              <span>{{ isRent ? '租金' : '货款' }}</span>
              <span class="fee-value">
                <template v-if="isRent && byDay && rentDays > 0">
                  ¥{{ formatMoney(resource.price) }} × {{ rentDays }} 天
                </template>
                <template v-else>¥{{ formatMoney(resource.price) }}</template>
              </span>
            </div>

            <div class="fee-row">
              <span>小计</span>
              <span class="fee-value">¥{{ formatMoney(totalAmount) }}</span>
            </div>

            <div v-if="isRent" class="fee-row">
              <span>押金<em class="deposit-note">(归还后退还)</em></span>
              <span class="fee-value">¥{{ formatMoney(deposit) }}</span>
            </div>

            <el-divider />

            <div class="fee-row total-row">
              <span>应付总额</span>
              <span class="total-value">¥{{ formatMoney(payAmount) }}</span>
            </div>

            <el-button
              type="primary"
              size="large"
              class="btn-block submit-btn"
              :disabled="!canSubmit"
              :loading="submitting"
              @click="submit"
            >
              {{ isRent ? '提交预约' : '提交购买申请' }}
            </el-button>

            <p class="submit-hint">
              {{ !isRent ? '提交后由发布者确认,确认后再付款'
                : rentDays <= 0 ? '请先选择租期'
                : needTraining && !trainingConfirmed ? '请先勾选安全须知'
                : '提交后由发布者确认,确认后再付款' }}
            </p>
          </section>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.order-create {
  padding-top: var(--space-xl);
  padding-bottom: var(--space-2xl);
}

.loading-wrap {
  max-width: 720px;
}

.page-head {
  margin-bottom: var(--space-lg);
}

.page-head h1 {
  font-size: var(--font-size-xl);
  margin-bottom: var(--space-xs);
}

.create-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: var(--space-lg);
  align-items: start;
}

.block {
  padding: var(--space-lg);
  margin-bottom: var(--space-md);
}

.block-title {
  font-size: var(--font-size-md);
  margin-bottom: var(--space-md);
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

/* ── 资源摘要 ─────────────────────────────────────────────── */
.res-row {
  display: flex;
  gap: var(--space-md);
}

.res-cover {
  width: 120px;
  height: 90px;
  object-fit: cover;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  flex-shrink: 0;
}

.res-info {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  min-width: 0;
}

.res-name {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.res-name:hover {
  color: var(--color-primary);
}

.res-meta {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.res-price .price-num {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-danger);
  font-family: var(--font-family-number);
}

/* ── 表单区 ───────────────────────────────────────────────── */
.date-picker {
  width: 100%;
  max-width: 380px;
}

.hint {
  margin-top: var(--space-sm);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  display: flex;
  align-items: center;
  gap: 4px;
}

.training-block {
  border-left: 3px solid var(--color-warning);
}

.warn-icon {
  color: var(--color-warning);
}

.training-content {
  background: var(--color-warning-light);
  border-radius: var(--radius-md);
  padding: var(--space-md);
  font-size: var(--font-size-sm);
  line-height: 1.7;
  color: var(--color-text-regular);
  margin-bottom: var(--space-md);
}

.training-check {
  font-weight: var(--font-weight-medium);
}

/* ── 费用明细 ─────────────────────────────────────────────── */
.side-col {
  position: sticky;
  top: 88px;
}

.summary .block-title {
  margin-bottom: var(--space-md);
}

.fee-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding: var(--space-sm) 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-regular);
}

.fee-value {
  font-family: var(--font-family-number);
  color: var(--color-text-primary);
}

.deposit-note {
  font-style: normal;
  color: var(--color-text-placeholder);
  font-size: var(--font-size-xs);
}

.total-row {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.total-value {
  font-size: var(--font-size-xl);
  color: var(--color-danger);
  font-family: var(--font-family-number);
  font-weight: var(--font-weight-bold);
}

.submit-btn {
  margin-top: var(--space-md);
}

.submit-hint {
  margin-top: var(--space-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-placeholder);
  text-align: center;
}

@media (max-width: 900px) {
  .create-grid {
    grid-template-columns: 1fr;
  }

  .side-col {
    position: static;
  }
}
</style>
