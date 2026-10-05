import request from './request'

/**
 * 订单相关接口。
 *
 * 下单请求里刻意不带单价、总额、押金 —— 这些由后端按资源当前数据算出来。
 * 凡是能换算成钱的字段,只要允许前端传入就等于把定价权交给了浏览器。
 */

/** 下单 */
export function createOrder(data) {
  return request.post('/order/create', data)
}

/** 我买到的 */
export function getMyPurchases(params) {
  return request.get('/order/mine', { params })
}

/** 我卖出的(待我处理) */
export function getMySales(params) {
  return request.get('/order/sold', { params })
}

/** 订单详情 */
export function getOrderDetail(id) {
  return request.get(`/order/detail/${id}`)
}

// ── 状态流转 ──────────────────────────────────────────────────

export function confirmOrder(id) {
  return request.put(`/order/${id}/confirm`)
}

export function rejectOrder(id, reason) {
  return request.put(`/order/${id}/reject`, { reason })
}

export function payOrder(id, payType = 1) {
  return request.put(`/order/${id}/pay`, null, { params: { payType } })
}

export function deliverOrder(id) {
  return request.put(`/order/${id}/deliver`)
}

export function receiveOrder(id) {
  return request.put(`/order/${id}/receive`)
}

export function returnOrder(id) {
  return request.put(`/order/${id}/return`)
}

export function cancelOrder(id, reason) {
  return request.put(`/order/${id}/cancel`, { reason })
}

// ── 后台 ──────────────────────────────────────────────────────

/** 后台订单列表(受数据级权限约束) */
export function getAdminOrders(params) {
  return request.get('/admin/order/list', { params })
}

/** 后台强制取消订单 */
export function adminCancelOrder(id, reason) {
  return request.put(`/admin/order/${id}/cancel`, { reason })
}
