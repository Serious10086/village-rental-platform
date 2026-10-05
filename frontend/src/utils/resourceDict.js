/**
 * 资源相关的展示常量。
 *
 * 集中放这里,避免同一套字典在列表页、详情页、我的发布里各写一遍 ——
 * 其中一处改了另两处没改,就会出现"同一个状态在两个页面上叫法不一样"。
 */
import { formatMoney } from '@/utils/format'

/** 交易方式 */
export const TRADE_TYPE = {
  1: { label: '出租', type: 'primary' },
  2: { label: '出售', type: 'warning' }
}

/** 资源状态 */
export const RESOURCE_STATUS = {
  0: { label: '待审核', type: 'info' },
  1: { label: '已上架', type: 'success' },
  2: { label: '已下架', type: 'warning' },
  3: { label: '交易中', type: 'primary' },
  4: { label: '已完成', type: 'success' },
  5: { label: '审核驳回', type: 'danger' },
  6: { label: '违规下架', type: 'danger' }
}

/** 计价单位 */
export const PRICE_UNIT = {
  1: '元/天',
  2: '元/次',
  3: '元/件'
}

/** 新旧程度 */
export const OLD_DEGREE = {
  1: '全新',
  2: '九成新',
  3: '七成新',
  4: '五成新及以下'
}

/** 交货方式 */
export const DELIVERY_TYPE = {
  1: '自提',
  2: '协商'
}

/** 风险等级 */
export const RISK_LEVEL = {
  1: { label: '低风险', type: 'success' },
  2: { label: '中风险', type: 'warning' },
  3: { label: '高风险', type: 'danger' }
}

/** 星期 */
export const WEEKDAYS = [
  { value: 1, label: '周一' },
  { value: 2, label: '周二' },
  { value: 3, label: '周三' },
  { value: 4, label: '周四' },
  { value: 5, label: '周五' },
  { value: 6, label: '周六' },
  { value: 7, label: '周日' }
]

/** 把 [1,2,3] 渲染成"周一、周二、周三";全选则显示"每天" */
export function formatWeekdays(list) {
  if (!list || list.length === 0) return '—'
  if (list.length === 7) return '每天'
  return list
    .slice()
    .sort((a, b) => a - b)
    .map((d) => WEEKDAYS.find((w) => w.value === d)?.label || `周${d}`)
    .join('、')
}

/** 价格展示:出租带单位,出售不带 */
export function formatPrice(row) {
  if (!row || row.price == null) return '—'
  if (row.tradeType === 2) return `¥${formatMoney(row.price)}`
  const unit = PRICE_UNIT[row.priceUnit] || '元/天'
  return `¥${formatMoney(row.price)}/${unit.replace('元/', '')}`
}
