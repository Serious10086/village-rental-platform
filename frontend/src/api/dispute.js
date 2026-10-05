import request from './request'

/**
 * 交易纠纷接口。
 *
 * 【前台没有"处理纠纷"这个函数,是刻意的】
 * 给出结论是管理员的活。接口文件里找不到处理入口,
 * 权限边界在接口划分上就是可见的 —— 想加也得先想清楚为什么加。
 *
 * 【纠纷不动钱、不改信用分】
 * 调解结果要退款,管理员走「强制取消订单」;要扣分,走「信用分调整」。
 * 这两个动作各自有完整的状态校验和流水记录。在这里再写一套退款/扣分,
 * 只会让同一件事出现两个口径 —— 而其中一个必然先过期。
 */

/** 申请纠纷。orderId + disputeType(1 损坏赔偿 2 押金争议 3 实物不符 4 逾期 5 其他) */
export function applyDispute(data) {
  return request.post('/dispute/apply', data)
}

/** 撤销纠纷(仅申请人本人、且管理员尚未处理时可用) */
export function cancelDispute(id) {
  return request.put(`/dispute/${id}/cancel`)
}

/** 与我有关的纠纷(我是申请人或被申请人都返回) */
export function getMyDisputes(params) {
  return request.get('/dispute/mine', { params })
}

/** 某笔订单的纠纷记录 —— 订单详情页据此决定显示「申请纠纷」还是「查看纠纷」 */
export function getDisputesByOrder(orderId) {
  return request.get(`/dispute/order/${orderId}`)
}

/** 纠纷详情(仅申请人、被申请人、有权管辖的管理员可见) */
export function getDisputeDetail(id) {
  return request.get(`/dispute/${id}`)
}

// ── 后台 ──────────────────────────────────────────────────────

/** 后台纠纷列表(受数据级权限约束,待受理/处理中的自动排前面) */
export function getAdminDisputes(params) {
  return request.get('/admin/dispute/list', { params })
}

/**
 * 后台纠纷详情。
 *
 * 和前台那个 /dispute/{id} 是同一份数据,但权限口径不同:
 * 前台那个靠"我是当事人或我有管辖权",这个直接挂在 dispute:handle
 * 权限码上。后台抽屉用这一个,权限边界在接口上一眼可见。
 */
export function getAdminDisputeDetail(id) {
  return request.get(`/admin/dispute/${id}`)
}

/** 处理纠纷。status:2 已调解 3 已关闭。订单会恢复到申请前的状态 */
export function handleDispute(id, data) {
  return request.put(`/admin/dispute/${id}/handle`, data)
}
