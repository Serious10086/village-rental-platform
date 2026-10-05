import request from './request'

/**
 * 钱包 / 提现接口。
 *
 * 【余额和收益明细的关系】
 * balance 不是"另算"出来的一个数,它恒等于
 *   「货款结算流水合计」−「已占用在提现里的钱(status 0 待审核 / 1 已打款)」
 * 所以收益明细页不是可有可无的装饰 —— 它是余额的账本,
 * 用户对着流水能自己把余额加出来。
 *
 * 【能提的只有货款】
 * 押金从来不进卖家余额(pay_type=3 是原路退回买家的),
 * 所以这里看到的每一笔都是租金/货款,不存在"把买家的押金提走"。
 */

/** 钱包概览:余额、累计收入、累计已提现、在途提现、单笔最低提现额 */
export function getWalletInfo() {
  return request.get('/wallet/info')
}

/** 收益明细(我的资金流水,受后端 direction 区分收/支) */
export function getEarnings(params) {
  return request.get('/wallet/earnings', { params })
}

/** 申请提现。accountType:1 微信 2 支付宝 3 银行卡 */
export function applyWithdraw(data) {
  return request.post('/wallet/withdraw', data)
}

/** 我的提现记录 */
export function getMyWithdraws(params) {
  return request.get('/wallet/withdraws', { params })
}

// ── 后台 ──────────────────────────────────────────────────────

/** 提现申请列表(受数据级权限约束,村管只看本村,待审核排前面) */
export function getAdminWithdraws(params) {
  return request.get('/admin/withdraw/list', { params })
}

/** 审核提现。pass=false 时 remark 必填(驳回必须说明理由) */
export function auditWithdraw(id, data) {
  return request.put(`/admin/withdraw/${id}/audit`, data)
}
