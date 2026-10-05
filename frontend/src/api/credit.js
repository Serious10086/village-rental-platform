import request from './request'

/**
 * 信用相关接口。
 *
 * 前台只能查自己的信用流水 —— 流水里写着扣分的具体原因,
 * 不该提供"查别人流水"的入口。别人信用分的高低,
 * 在订单详情页能看到一个数字就够了。
 */

/** 我的信用概览(分数、等级、三个比率) */
export function getMyCredit() {
  return request.get('/credit/mine')
}

/** 我的信用变动流水 */
export function getMyCreditRecords(params) {
  return request.get('/credit/records', { params })
}

// ── 后台 ──────────────────────────────────────────────────────

/** 后台信用列表(受数据级权限约束,按信用分升序) */
export function getAdminCreditList(params) {
  return request.get('/admin/credit/list', { params })
}

/** 指定用户的信用流水 */
export function getUserCreditRecords(params) {
  return request.get('/admin/credit/records', { params })
}

/** 手动调整信用分 */
export function adjustCredit(data) {
  return request.post('/admin/credit/adjust', data)
}
