<script setup>
/**
 * 资源卡片。
 *
 * 列表页、首页、我的发布都用它 —— 抽出来的主要理由不是"少写几行",
 * 而是保证同一份数据在所有入口的呈现一致:价格格式、状态标签、
 * 高风险提示这些细节只在这个文件里定义一次。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { TRADE_TYPE, RESOURCE_STATUS, RISK_LEVEL, formatPrice, formatWeekdays } from '@/utils/resourceDict'
import { formatMoney } from '@/utils/format'

const props = defineProps({
  resource: { type: Object, required: true },
  /** 是否显示状态标签(我的发布、后台需要,前台不用 —— 前台只有已上架的) */
  showStatus: { type: Boolean, default: false },
  /**
   * 我是从哪儿点进详情页的,会写进路由 query 传给详情页。
   *
   * 详情页据此上报"这次浏览的来源",后台的**推荐位点击率**就是靠它算的 ——
   * 推荐位传 {@code 'rec'},列表页传 {@code 'list'},不传就是"直接进来的"。
   *
   * 为什么用 query 而不是别的:一次跳转的"从哪来"是**这一跳的属性**,
   * 不是全局状态。放进 URL 里,刷新页面、复制链接、前进后退都还成立;
   * 放进 Pinia 或 sessionStorage 则会在刷新后失真,
   * 而且用户开两个标签页时后开的那个会把先开的覆盖掉。
   */
  from: { type: String, default: '' }
})

const router = useRouter()

const trade = computed(() => TRADE_TYPE[props.resource.tradeType] || {})
const status = computed(() => RESOURCE_STATUS[props.resource.resourceStatus])
const risk = computed(() => RISK_LEVEL[props.resource.riskLevel])

/** 封面图为空时的兜底。真实感靠"有图",所以要给一个像样的占位而不是空白 */
const cover = computed(() => props.resource.coverUrl || '')

function goDetail() {
  router.push({
    path: `/resource/${props.resource.id}`,
    query: props.from ? { from: props.from } : {}
  })
}
</script>

<template>
  <article class="resource-card" @click="goDetail">
    <div class="cover">
      <img v-if="cover" :src="cover" :alt="resource.resourceName" loading="lazy" />
      <div v-else class="cover-placeholder">
        <el-icon :size="30"><Picture /></el-icon>
      </div>

      <!-- 角标:交易方式 -->
      <span class="badge badge-trade" :class="`is-${resource.tradeType}`">
        {{ trade.label }}
      </span>

      <!-- 角标:风险提示。只在需要培训的高风险资源上出现 -->
      <span v-if="resource.needTraining" class="badge badge-risk">
        <el-icon :size="12"><WarningFilled /></el-icon>
        需培训
      </span>
    </div>

    <div class="body">
      <h3 class="name" :title="resource.resourceName">{{ resource.resourceName }}</h3>

      <div class="meta">
        <span class="tag">{{ resource.categoryName }}</span>
        <span class="dot">·</span>
        <span class="village">{{ resource.villageName }}</span>
      </div>

      <div class="price-row">
        <span class="price num">{{ formatPrice(resource) }}</span>
        <span v-if="resource.tradeType === 1 && resource.deposit > 0" class="deposit">
          押金 ¥{{ formatMoney(resource.deposit) }}
        </span>
      </div>

      <!-- 可租时段:出租类才有,让租客快速判断时间是否合适 -->
      <div v-if="resource.tradeType === 1 && resource.rentableWeekdays" class="weekdays">
        <el-icon :size="12"><Calendar /></el-icon>
        {{ formatWeekdays(String(resource.rentableWeekdays).split(',').map(Number)) }}
      </div>

      <div class="footer">
        <div class="publisher">
          <el-avatar :size="20" :src="resource.publisherAvatar">
            {{ (resource.publisherName || '?').charAt(0) }}
          </el-avatar>
          <span class="publisher-name">{{ resource.publisherName }}</span>
          <span class="credit num">{{ resource.publisherCredit }}分</span>
        </div>

        <span v-if="showStatus && status" class="status-tag">
          <el-tag :type="status.type" size="small" effect="light">{{ status.label }}</el-tag>
        </span>
        <span v-else class="views num">
          <el-icon :size="12"><View /></el-icon>
          {{ resource.viewCount }}
        </span>
      </div>
    </div>
  </article>
</template>

<style scoped>
.resource-card {
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  transition: all var(--duration-normal) var(--ease-out);
}

.resource-card:hover {
  border-color: var(--color-primary-border);
  box-shadow: var(--shadow-md);
  /* 轻微上浮,让"可点击"这件事在视觉上成立 */
  transform: translateY(-3px);
}

/* ── 封面 ────────────────────────────────────────────────── */
.cover {
  position: relative;
  aspect-ratio: 4 / 3;
  background: var(--color-info-light);
  overflow: hidden;
}

.cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform var(--duration-normal) var(--ease-out);
}

.resource-card:hover .cover img {
  transform: scale(1.04);
}

.cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-placeholder);
}

.badge {
  position: absolute;
  top: var(--space-sm);
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: 1.7;
}

.badge-trade {
  left: var(--space-sm);
  color: #fff;
}

/* 出租用主色、出售用橙色 —— 和字典里的 tag 类型保持一致 */
.badge-trade.is-1 {
  background: var(--color-primary);
}

.badge-trade.is-2 {
  background: var(--color-warning);
}

.badge-risk {
  right: var(--space-sm);
  background: var(--color-danger);
  color: #fff;
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

/* ── 内容 ────────────────────────────────────────────────── */
.body {
  padding: var(--space-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  flex: 1;
}

.name {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
  line-height: 1.4;
  /* 名称可能很长,限制两行,超出省略 —— 保证卡片高度一致 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 2.8em;
}

.meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  display: flex;
  align-items: center;
  gap: 4px;
}

.tag {
  background: var(--color-primary-light);
  color: var(--color-primary);
  padding: 1px 6px;
  border-radius: var(--radius-sm);
}

.dot {
  color: var(--color-text-disabled);
}

.price-row {
  display: flex;
  align-items: baseline;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.price {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-danger);
}

.deposit {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.weekdays {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.footer {
  margin-top: auto;
  padding-top: var(--space-sm);
  border-top: 1px solid var(--color-border-light);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
}

.publisher {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  min-width: 0;
}

.publisher-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.credit {
  color: var(--color-success);
  flex-shrink: 0;
}

.views {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: var(--font-size-xs);
  color: var(--color-text-placeholder);
  flex-shrink: 0;
}
</style>
