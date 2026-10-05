/**
 * 订单相关的展示常量。
 *
 * 与 utils/resourceDict.js 同样的理由:状态字典集中一处,
 * 列表页、详情页、后台管理页共用。分散写的话,后端改了文案
 * 或者加了新状态,总有一处忘了改,界面就会自相矛盾。
 */

import { formatTimeOnly } from './format'

/** 订单状态。step 表示它落在时间轴的第几步,0 表示不在正常主线上(取消/拒绝) */
export const ORDER_STATUS = {
  0: { label: '待卖家确认', type: 'warning', step: 1 },
  1: { label: '待付款', type: 'danger', step: 2 },
  2: { label: '待交付', type: 'primary', step: 3 },
  3: { label: '使用中', type: 'primary', step: 4 },
  4: { label: '已完成', type: 'success', step: 5 },
  5: { label: '已取消', type: 'info', step: 0 },
  6: { label: '纠纷中', type: 'danger', step: 0 },
  7: { label: '卖家已拒绝', type: 'info', step: 0 }
}

/** 订单类型 */
export const ORDER_TYPE = {
  1: { label: '租赁', type: 'primary' },
  2: { label: '购买', type: 'warning' }
}

/** 支付状态 */
export const PAY_STATUS = {
  0: '未支付',
  1: '已支付',
  2: '已退款'
}

/** 资金流水类型 */
export const PAY_TYPE = {
  1: '微信支付(模拟)',
  2: '支付宝(模拟)',
  3: '押金退还',
  4: '货款结算',
  5: '提现打款'
}

/** 我在订单中的身份 */
export const MY_ROLE = {
  buyer: '买家',
  seller: '卖家',
  admin: '管理员'
}

/**
 * 动作码 → 按钮文案与样式。
 *
 * 动作码由后端按状态机算出来,前端只负责"把码翻译成按钮",
 * 不自己判断"什么状态该显示什么按钮"。这样后端加了新状态、
 * 改了流转规则,前端不同步也不会露出"按钮点了报错"的破绽。
 */
export const ORDER_ACTION = {
  confirm: { label: '确认接单', type: 'primary' },
  reject: { label: '拒绝', type: 'danger', plain: true },
  pay: { label: '去支付', type: 'primary' },
  cancel: { label: '取消订单', type: 'info', plain: true },
  deliver: { label: '确认交付', type: 'primary' },
  receive: { label: '确认收货', type: 'success' },
  return: { label: '确认归还', type: 'success' }
}

/**
 * 按订单数据生成时间轴节点。
 *
 * 五个时刻是后端订单表上的五列(而不是一张状态变更日志表),
 * 所以时间轴可以在前端直接拼出来,不用再发一个"查订单轨迹"的请求。
 *
 * 时间只显示到分钟:五个节点挤在一列里,每个都带完整日期会把时间轴
 * 撑得很长,而这些节点通常发生在同几天内,日期是冗余信息。
 */
export function buildTimeline(order) {
  if (!order) return []
  const isRent = order.orderType === 1

  const nodes = [
    { key: 'create', title: '提交订单', time: formatTimeOnly(order.createTime), desc: '买家发起订单' },
    { key: 'confirm', title: '卖家确认', time: formatTimeOnly(order.confirmTime), desc: '卖家接单,订单成立' },
    { key: 'pay', title: '买家付款', time: formatTimeOnly(order.payTime), desc: order.payTime ? '支付已完成' : '等待买家付款' }
  ]

  // 租赁有"交付 → 使用中 → 归还"三步;购买只有"确认收货"一步
  if (isRent) {
    nodes.push({
      key: 'deliver', title: '卖家交付', time: formatTimeOnly(order.deliverTime),
      desc: order.deliverTime ? '资源已交付买家使用' : '等待卖家交付'
    })
    nodes.push({
      key: 'return', title: '买家归还', time: formatTimeOnly(order.returnTime),
      desc: '归还后押金原路退还'
    })
  } else {
    nodes.push({
      key: 'receive', title: '买家收货', time: formatTimeOnly(order.returnTime),
      desc: '确认收货后订单完成'
    })
  }

  nodes.push({
    key: 'finish', title: '订单完成', time: formatTimeOnly(order.finishTime),
    desc: '双方信用分 +2'
  })

  // 已取消 / 已拒绝的订单不走主线,单独给一个终止节点
  if (order.orderStatus === 5 || order.orderStatus === 7) {
    nodes.push({
      key: 'cancel', title: order.orderStatus === 5 ? '订单取消' : '卖家拒绝',
      time: formatTimeOnly(order.finishTime), desc: order.cancelReason || ''
    })
  }

  return nodes
}
