<script setup>
/**
 * 资源详情页。
 *
 * 布局是"主信息区 + 右侧操作卡":左边放图、描述、规则、评价,
 * 右边放价格和操作按钮。这样价格和"立即预约"始终在视野内,
 * 用户往下滚动看描述时不用来回翻。
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getResourceDetail } from '@/api/resource'
import { getEvaluationsByResource } from '@/api/evaluation'
import { toggleFavorite } from '@/api/favorite'
import { getSimilarResources } from '@/api/recommend'
import { useUserStore } from '@/stores/user'
import EvaluationList from '@/components/EvaluationList.vue'
import ResourceCard from '@/components/ResourceCard.vue'
import { formatDateTime, formatMoney } from '@/utils/format'
import {
  TRADE_TYPE, RESOURCE_STATUS, RISK_LEVEL, OLD_DEGREE, DELIVERY_TYPE,
  formatWeekdays, formatPrice
} from '@/utils/resourceDict'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loading = ref(true)
const detail = ref(null)
const activeImage = ref(0)

const evaluations = ref([])
const evalTotal = ref(0)

const trade = computed(() => TRADE_TYPE[detail.value?.tradeType] || {})
const status = computed(() => RESOURCE_STATUS[detail.value?.resourceStatus] || {})
const risk = computed(() => RISK_LEVEL[detail.value?.riskLevel] || {})

/** 图集:没有图时给一个占位,避免整个轮播区域塌掉 */
const gallery = computed(() => {
  const list = detail.value?.images?.length ? detail.value.images : []
  if (list.length === 0 && detail.value?.coverUrl) {
    return [detail.value.coverUrl]
  }
  return list
})

/** 发布人是否就是当前用户 —— 是的话不显示"租自己的东西" */
const isOwner = computed(() => detail.value?.isOwner === true)

/** 已上架的资源才能下单 */
const canOrder = computed(() => detail.value?.resourceStatus === 1 && !isOwner.value)

// ── 收藏(批次 8) ─────────────────────────────────────────────
const favorited = ref(false)
const togglingFav = ref(false)

/**
 * 收藏 / 取消收藏。
 *
 * 【星星的状态以服务端返回为准,不在本地取反】
 * 本地取反看起来更"快",但它在两个场景下会错:另一个标签页里取消过、
 * 或者上一次请求其实失败了却被乐观更新掩盖。服务端返回的就是
 * 操作之后的真实状态,直接赋值 —— 一个请求本来就只有几百毫秒。
 */
async function handleToggleFavorite() {
  if (!userStore.isLoggedIn) {
    ElMessage.warning('登录后才能收藏')
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  togglingFav.value = true
  try {
    const nowFavorited = await toggleFavorite(detail.value.id)
    favorited.value = nowFavorited === true
    ElMessage.success(favorited.value ? '已收藏' : '已取消收藏')
  } catch (e) {
    // 提示由 request.js 统一弹
  } finally {
    togglingFav.value = false
  }
}

// ── 相似资源(批次 8) ─────────────────────────────────────────
const similar = ref([])

async function loadSimilar() {
  try {
    similar.value = (await getSimilarResources(detail.value.id, 4)) || []
  } catch (e) {
    // 相似资源失败不该影响详情页本身 —— 它是一块附加内容
    console.warn('相似资源加载失败', e)
  }
}

// ── 浏览上报(批次 8) ─────────────────────────────────────────
//
// 【为什么由前端上报,而不是后端在详情接口里顺手记】
// 后端拿不到两样东西:停留时长、以及"从哪儿点进来的"。而这两样正是
// 推荐算法和推荐位点击率需要的。代价是前端不报就没有记录。
const VIEW_SOURCE = { list: 1, detail: 2, search: 3, rec: 4 }

const enteredAt = Date.now()
let viewReported = false

/**
 * 上报这次浏览,只报一次。
 *
 * 【为什么用原生 fetch 而不是项目的 axios 封装】
 * 因为只有 fetch 能带 `keepalive`。用户直接关标签页、或者在地址栏里
 * 敲另一个网址时,页面正在被销毁 —— 普通请求会被浏览器直接掐掉,
 * 而 `keepalive: true` 的那一个会被放行到发完为止。
 * **这不是优化,是"报得出去"和"报不出去"的区别**:第一版只用
 * onBeforeUnmount,在整页跳转时它根本不触发(那是 SPA 路由切换才有的钩子),
 * 于是从推荐位点进详情页、再点个链接离开,这次点击就永远丢了。
 *
 * 【为什么三个触发点都留着】
 *   visibilitychange → 切到别的标签页 / 切到别的应用(移动端尤其常见)
 *   pagehide         → 整页跳转、关标签页、进 bfcache
 *   onBeforeUnmount  → 应用内路由切换(最常规的一条)
 * 三条覆盖的离开方式不一样,靠任何一个都会漏。
 *
 * 重复上报由 viewReported 挡掉:一次访问只记一个停留时长。
 * 反复报会把同一个停留时长记成好几条,把"认真看了多久"这个信号稀释掉。
 */
function reportViewOnce() {
  if (viewReported || !detail.value) {
    return
  }
  viewReported = true
  const seconds = Math.round((Date.now() - enteredAt) / 1000)
  const token = localStorage.getItem('token')
  fetch('/api/behavior/view', {
    method: 'POST',
    keepalive: true,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify({
      resourceId: detail.value.id,
      staySeconds: seconds,
      source: VIEW_SOURCE[route.query.from] || 2
    })
  }).catch(() => {
    // 上报失败就算了 —— 它是个旁路,不该影响用户接下来干什么,
    // 也不该在控制台里留下一条红字让看的人以为页面坏了
  })
}

function onVisibilityChange() {
  if (document.hidden) {
    reportViewOnce()
  }
}

/**
 * 下单按钮是否禁用。
 *
 * 【设计取舍:未登录时不禁用,而是让点击后跳登录】
 * 禁用 + tooltip 的做法看似更"严谨",但用户的第一反应是"这个按钮坏了" ——
 * 他得先悬停才能读到原因。允许点击并在点击时跳转登录页(带 redirect 参数,
 * 登录后回到当前页),是更顺的引导路径。
 *
 * 只有"登录了但没实名"才真正禁用:这种情况点击也无法自动解决,
 * 必须先去个人中心提交认证,禁用 + 说明是恰当的。
 */
const orderDisabled = computed(() =>
  !canOrder.value || (userStore.isLoggedIn && !userStore.isRealnameVerified)
)

const orderDisabledReason = computed(() => {
  if (!canOrder.value) return '该资源当前不可下单'
  if (userStore.isLoggedIn && !userStore.isRealnameVerified) return '请先完成实名认证'
  return ''
})

async function fetchDetail() {
  loading.value = true
  try {
    detail.value = await getResourceDetail(route.params.id)
    activeImage.value = 0
    // 收藏状态由详情接口一起返回(服务端算好的),不用前端再拉一遍收藏列表
    favorited.value = detail.value?.favorited === true
  } catch (e) {
    detail.value = null
  } finally {
    loading.value = false
  }
  // 评价和相似资源单独取:资源取不到时也该继续尝试,
  // 而且它们失败不该让整个详情页白屏
  loadEvaluations()
  if (detail.value) {
    loadSimilar()
  }
}

/** 评价列表与均分。均分只按当前页算,超过一页时页面上会标注清楚 */
const evalSummary = computed(() => {
  const list = evaluations.value
  if (!list.length) return { count: 0, avg: 0 }
  const sum = list.reduce((acc, e) => acc + (e.score || 0), 0)
  return { count: evalTotal.value, avg: sum / list.length }
})

async function loadEvaluations() {
  try {
    const data = await getEvaluationsByResource(route.params.id, { pageSize: 20 })
    evaluations.value = data.rows || []
    evalTotal.value = data.total || 0
  } catch (e) {
    evaluations.value = []
  }
}

function handleOrder() {
  if (!userStore.isLoggedIn) {
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (!userStore.isRealnameVerified) {
    ElMessage.warning('请先完成实名认证后再下单')
    return
  }
  // 下单走独立的确认页而不是弹窗:租赁要选日期、要实时算钱、
  // 高风险资源还要勾安全确认 —— 塞进弹窗里用户看不到自己在选什么
  router.push(`/order/create/${detail.value.id}`)
}

function editResource() {
  router.push(`/publish/edit/${detail.value.id}`)
}

/**
 * 联系发布者。
 *
 * 这里**不调 startChat**,只把(对象, 资源)带进消息页,由那边负责
 * 把会话找出来或建出来。会话的创建入口收在一处,是因为"按 (双方, 资源)
 * 复用已有会话"这条规则要是散在多个页面里,迟早有一处写成"每次都新建",
 * 表现为同一个人围绕同一件东西聊出了好几个会话。
 */
function contactPublisher() {
  if (!userStore.isLoggedIn) {
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  router.push({
    path: '/messages',
    query: { to: detail.value.publisherId, resourceId: detail.value.id }
  })
}

onMounted(() => {
  fetchDetail()
  // 三条离开路径都要挂上 —— 它们覆盖的场景不一样,靠任何一个都会漏
  // (详见 reportViewOnce 的注释)
  document.addEventListener('visibilitychange', onVisibilityChange)
  window.addEventListener('pagehide', reportViewOnce)
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
  window.removeEventListener('pagehide', reportViewOnce)
  // 应用内路由切换走这里 —— 它是三条里最常规的一条,
  // 但**只覆盖 SPA 内部跳转**:整页跳转和关标签页要靠 pagehide
  reportViewOnce()
})
</script>

<template>
  <div class="page-container">
    <div v-loading="loading" class="detail-wrap">
      <template v-if="detail">
        <!-- ── 面包屑 ──────────────────────────────────────── -->
        <el-breadcrumb separator="/" class="crumb">
          <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
          <el-breadcrumb-item :to="{ path: '/resource' }">浏览资源</el-breadcrumb-item>
          <el-breadcrumb-item
            :to="{ path: '/resource', query: { categoryId: detail.categoryId } }"
          >{{ detail.categoryName }}</el-breadcrumb-item>
          <el-breadcrumb-item>{{ detail.resourceName }}</el-breadcrumb-item>
        </el-breadcrumb>

        <div class="detail-grid">
          <!-- ══ 左侧:图集 + 信息 ═══════════════════════════ -->
          <div class="left">
            <!-- 图集 -->
            <div class="gallery">
              <div class="main-image">
                <img
                  v-if="gallery.length"
                  :src="gallery[activeImage]"
                  :alt="detail.resourceName"
                />
                <div v-else class="image-placeholder">
                  <el-icon :size="44"><Picture /></el-icon>
                  <span>暂无图片</span>
                </div>

                <span class="badge badge-trade" :class="`is-${detail.tradeType}`">
                  {{ trade.label }}
                </span>
              </div>

              <div v-if="gallery.length > 1" class="thumbs">
                <div
                  v-for="(img, idx) in gallery"
                  :key="idx"
                  class="thumb"
                  :class="{ 'is-active': activeImage === idx }"
                  @click="activeImage = idx"
                >
                  <img :src="img" :alt="`图 ${idx + 1}`" />
                </div>
              </div>
            </div>

            <!-- 标题与状态 -->
            <div class="card title-card">
              <div class="title-row">
                <h1 class="title">{{ detail.resourceName }}</h1>
                <el-tag v-if="!isOwner" :type="status.type" effect="dark" size="small">
                  {{ status.label }}
                </el-tag>
                <el-tag v-else :type="status.type" size="small">
                  我发布的 · {{ status.label }}
                </el-tag>
              </div>

              <div class="title-meta">
                <span class="meta-item">
                  <el-icon :size="13"><PriceTag /></el-icon>{{ detail.categoryName }}
                </span>
                <span class="meta-item">
                  <el-icon :size="13"><Location /></el-icon>{{ detail.location }}
                </span>
                <span class="meta-item">
                  <el-icon :size="13"><View /></el-icon>{{ detail.viewCount }} 次浏览
                </span>
                <span class="meta-item">
                  <el-icon :size="13"><Timer /></el-icon>
                  {{ formatDateTime(detail.createTime) }}
                </span>
              </div>

              <div v-if="detail.tags?.length" class="tag-row">
                <el-tag
                  v-for="t in detail.tags"
                  :key="t"
                  size="small"
                  effect="plain"
                  type="info"
                >{{ t }}</el-tag>
              </div>

              <!-- 审核驳回/违规下架时给出原因,避免发布者一头雾水 -->
              <el-alert
                v-if="detail.offlineReason"
                type="warning"
                :closable="false"
                show-icon
                class="reason-alert"
                :title="`${status.label}原因:${detail.offlineReason}`"
              />
            </div>

            <!-- 高风险提示 —— 需求里"应急与安全管控"的落点 -->
            <div v-if="detail.needTraining" class="card risk-card">
              <div class="risk-head">
                <el-icon :size="18"><WarningFilled /></el-icon>
                <span>这是高风险资源({{ risk.label }}),使用前需确认安全须知</span>
              </div>
              <p class="risk-content">{{ detail.trainingContent }}</p>
            </div>

            <!-- 描述 -->
            <div class="card">
              <h2 class="section-title">资源描述</h2>
              <p class="desc">{{ detail.description || '发布者没有填写描述' }}</p>
            </div>

            <!-- 出租规则 -->
            <div v-if="detail.tradeType === 1" class="card">
              <h2 class="section-title">租赁说明</h2>
              <el-descriptions :column="1" border size="small">
                <el-descriptions-item label="可租时段">
                  {{ detail.rentStartDate || '—' }} 至 {{ detail.rentEndDate || '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="可租星期">
                  {{ formatWeekdays(detail.rentableWeekdays) }}
                </el-descriptions-item>
                <el-descriptions-item label="押金">
                  ¥{{ formatMoney(detail.deposit) }}
                </el-descriptions-item>
                <el-descriptions-item label="使用规则">
                  <span class="pre-wrap">{{ detail.useRule || '—' }}</span>
                </el-descriptions-item>
              </el-descriptions>
            </div>

            <!-- 出售信息 -->
            <div v-else class="card">
              <h2 class="section-title">交易信息</h2>
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="新旧程度">
                  {{ OLD_DEGREE[detail.oldDegree] || '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="交货方式">
                  {{ DELIVERY_TYPE[detail.deliveryType] || '—' }}
                </el-descriptions-item>
              </el-descriptions>
            </div>

            <!-- 用户评价 —— 只显示正常状态的,被管理员隐藏的不会出现在这里 -->
            <div class="card">
              <div class="eval-head">
                <h3>用户评价</h3>
                <div v-if="evalSummary.count" class="eval-summary">
                  <el-rate :model-value="evalSummary.avg" disabled size="small" />
                  <span class="text-sm text-secondary">
                    {{ evalSummary.avg.toFixed(1) }} 分 · {{ evalSummary.count }} 条
                  </span>
                </div>
              </div>
              <EvaluationList
                :evaluations="evaluations"
                empty-text="还没有人评价这个资源"
              />
            </div>
          </div>

          <!-- ══ 右侧:操作卡 ═══════════════════════════════ -->
          <aside class="right">
            <div class="card action-card">
              <div class="price-block">
                <span class="price num">{{ formatPrice(detail) }}</span>
                <span v-if="detail.tradeType === 1 && detail.deposit > 0" class="deposit">
                  押金 ¥{{ formatMoney(detail.deposit) }}
                </span>
              </div>

              <!-- 发布人 -->
              <div class="publisher-block">
                <el-avatar :size="40" :src="detail.publisherAvatar">
                  {{ (detail.publisherName || '?').charAt(0) }}
                </el-avatar>
                <div class="publisher-info">
                  <div class="publisher-name">{{ detail.publisherName }}</div>
                  <div class="publisher-meta">
                    <span class="credit num">信用 {{ detail.publisherCredit }} 分</span>
                    <span class="dot">·</span>
                    <span>成交 {{ detail.publisherOrderCount || 0 }} 单</span>
                  </div>
                </div>
              </div>

              <el-divider />

              <!-- 操作按钮 -->
              <template v-if="isOwner">
                <el-alert
                  type="info"
                  :closable="false"
                  title="这是你发布的资源"
                  description="可以编辑内容、调整价格或下架"
                  show-icon
                  class="owner-alert"
                />
                <el-button class="btn-block" @click="editResource">编辑资源</el-button>
                <el-button class="btn-block" @click="router.push('/my/resource')">
                  前往我的发布管理
                </el-button>
              </template>

              <template v-else>
                <el-tooltip
                  :disabled="!orderDisabledReason"
                  :content="orderDisabledReason"
                  placement="top"
                >
                  <div class="btn-wrap">
                    <el-button
                      type="primary"
                      size="large"
                      class="btn-block"
                      :disabled="orderDisabled"
                      @click="handleOrder"
                    >
                      {{ detail.tradeType === 1 ? '立即预约' : '立即购买' }}
                    </el-button>
                  </div>
                </el-tooltip>

                <el-button class="btn-block" @click="contactPublisher">
                  <el-icon><ChatDotRound /></el-icon> 联系发布者
                </el-button>

                <p class="action-hint">
                  {{ detail.tradeType === 1
                    ? '预约后由发布者确认,再进入支付环节'
                    : '提交购买申请后由发布者确认' }}
                </p>
              </template>

              <!--
                收藏对**所有人**都显示,包括发布者本人。
                自己的东西也可以收藏 —— 收藏在语义上是"我记着这个",
                不是"我想租它"。把它和"不能下单自己发布的资源"区分开:
                后者是交易规则,前者只是个人的一个书签。
              -->
              <el-button
                class="btn-block fav-btn"
                :loading="togglingFav"
                @click="handleToggleFavorite"
              >
                <el-icon>
                  <component :is="favorited ? StarFilled : Star" />
                </el-icon>
                {{ favorited ? '已收藏' : '收藏' }}
              </el-button>
            </div>

            <!-- 安全提示 -->
            <div class="card safety-card">
              <h3 class="safety-title">
                <el-icon :size="15"><Lock /></el-icon> 交易提醒
              </h3>
              <ul class="safety-list">
                <li>交易前请先确认实物状况,建议当面查验</li>
                <li>租金与押金通过平台支付,不要私下转账</li>
                <li>归还时双方共同确认设备状态</li>
                <li>如有争议可申请村级管理员介入协调</li>
              </ul>
            </div>
          </aside>
        </div>

        <!-- ── 相似资源(批次 8) ─────────────────────────────── -->
        <!--
          放在两栏布局**下面**、占满整宽,而不是塞进右边的操作栏:
          它是"看完这个之后还可能想看什么",属于页面的延伸阅读,
          和右侧那些"对这个资源做什么"的操作是两回事。

          没有数据时整段不显示(而不是显示一个空框)——
          一个写着"暂无相似资源"的框只会告诉用户"这里本来该有东西"。
        -->
        <section v-if="similar.length" class="section similar-section">
          <div class="section-head">
            <h2 class="section-title">相似资源</h2>
            <span class="section-sub">
              和「{{ detail.categoryName }}」同类的其他资源
            </span>
          </div>
          <div class="similar-grid">
            <div v-for="item in similar" :key="item.id" class="similar-item">
              <div class="similar-reason">{{ item.reason }}</div>
              <ResourceCard :resource="item" from="rec" />
            </div>
          </div>
        </section>
      </template>

      <el-empty v-else-if="!loading" description="资源不存在或已下架">
        <el-button type="primary" @click="router.push('/resource')">去看看其他资源</el-button>
      </el-empty>
    </div>
  </div>
</template>

<style scoped>
.detail-wrap {
  min-height: 400px;
}

/* ── 收藏按钮 ──────────────────────────────────────────────── */
/* 和"立即预约""联系发布者"并排,所以间距跟它们一致 */
.fav-btn {
  margin-top: var(--space-sm);
}

/* ── 相似资源 ──────────────────────────────────────────────── */
.similar-section {
  margin-top: var(--space-xl);
}

.section-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-md);
  margin-bottom: var(--space-md);
}

.section-title {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.section-sub {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.similar-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: var(--space-md);
}

/* 理由挂在卡片上面而不是下面:它是"为什么你会看到它"的铺垫,
   先读到理由再看卡片才顺 */
.similar-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.similar-reason {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.crumb {
  margin-bottom: var(--space-md);
}

.detail-grid {
  display: grid;
  grid-template-columns: 1fr 340px;
  gap: var(--space-lg);
  align-items: start;
}

.left {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
  min-width: 0;
}

/* ── 图集 ────────────────────────────────────────────────── */
.gallery {
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-md);
}

.main-image {
  position: relative;
  aspect-ratio: 4 / 3;
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--color-info-light);
}

.main-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.image-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  color: var(--color-text-placeholder);
  font-size: var(--font-size-sm);
}

.badge {
  position: absolute;
  top: var(--space-md);
  left: var(--space-md);
  padding: 3px 12px;
  border-radius: var(--radius-full);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: #fff;
}

.badge-trade.is-1 {
  background: var(--color-primary);
}

.badge-trade.is-2 {
  background: var(--color-warning);
}

.thumbs {
  display: flex;
  gap: var(--space-sm);
  margin-top: var(--space-sm);
}

.thumb {
  width: 72px;
  height: 54px;
  border-radius: var(--radius-sm);
  overflow: hidden;
  border: 2px solid transparent;
  cursor: pointer;
  transition: border-color var(--duration-fast);
}

.thumb.is-active {
  border-color: var(--color-primary);
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* ── 卡片通用 ────────────────────────────────────────────── */
.card {
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-lg);
}

.section-title {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
  margin-bottom: var(--space-md);
  padding-bottom: var(--space-sm);
  border-bottom: 1px solid var(--color-border-light);
}

/* ── 标题卡 ──────────────────────────────────────────────── */
.title-row {
  display: flex;
  align-items: flex-start;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  line-height: 1.35;
  flex: 1;
  min-width: 0;
}

.title-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-md);
  margin-top: var(--space-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: var(--space-md);
}

.reason-alert {
  margin-top: var(--space-md);
}

.desc {
  white-space: pre-wrap;
  line-height: 1.9;
  color: var(--color-text-regular);
}

.pre-wrap {
  white-space: pre-wrap;
}

/* ── 高风险提示 ──────────────────────────────────────────── */
.risk-card {
  border-color: var(--color-danger);
  background: var(--color-danger-light);
}

.risk-head {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  color: var(--color-danger);
  font-weight: var(--font-weight-bold);
}

.risk-content {
  margin-top: var(--space-sm);
  font-size: var(--font-size-sm);
  line-height: 1.8;
  color: var(--color-text-regular);
  white-space: pre-wrap;
}

/* ── 右侧操作卡 ──────────────────────────────────────────── */
.right {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
  position: sticky;
  top: calc(var(--header-height) + var(--space-md));
}

.action-card {
  display: flex;
  flex-direction: column;
}

.price-block {
  display: flex;
  align-items: baseline;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.price {
  font-size: 28px;
  font-weight: var(--font-weight-bold);
  color: var(--color-danger);
  line-height: 1.2;
}

.deposit {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.publisher-block {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-top: var(--space-md);
}

.publisher-info {
  min-width: 0;
}

.publisher-name {
  font-weight: var(--font-weight-medium);
}

.publisher-meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  display: flex;
  align-items: center;
  gap: 4px;
}

.credit {
  color: var(--color-success);
}

.dot {
  color: var(--color-text-disabled);
}

.btn-block {
  width: 100%;
  margin-left: 0 !important;
  margin-bottom: var(--space-sm);
}

.btn-wrap {
  width: 100%;
}

.owner-alert {
  margin-bottom: var(--space-md);
}

.action-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-placeholder);
  text-align: center;
  line-height: 1.6;
}

/* ── 安全提示 ────────────────────────────────────────────── */
.safety-card {
  background: var(--color-info-light);
}

.safety-title {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-regular);
  margin-bottom: var(--space-sm);
}

.safety-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.safety-list li {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: 1.6;
  padding-left: 12px;
  position: relative;
}

.safety-list li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 7px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--color-text-disabled);
}

/* ── 响应式 ──────────────────────────────────────────────── */
@media (max-width: 1000px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }

  .right {
    position: static;
  }
}

/* ── 用户评价 ─────────────────────────────────────────────── */
.eval-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  margin-bottom: var(--space-sm);
}

.eval-head h3 {
  font-size: var(--font-size-md);
}

.eval-summary {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}
</style>
