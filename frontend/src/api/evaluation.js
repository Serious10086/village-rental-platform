import request from './request'

/**
 * 订单评价接口。
 *
 * 提交评价时只发 orderId 和评分 —— "我评的是谁"由服务端从订单里推出来。
 * 这不是省事,是安全:一旦允许前端传 toUserId,任何人都能伪造评价记录刷分。
 */

/** 提交评价 */
export function createEvaluation(data) {
  return request.post('/evaluation/create', data)
}

/** 回复评价(只有被评价人可回复,且只能回复一次) */
export function replyEvaluation(id, replyContent) {
  return request.put(`/evaluation/${id}/reply`, { replyContent })
}

/** 我收到的评价 */
export function getReceivedEvaluations(params) {
  return request.get('/evaluation/received', { params })
}

/** 我发出的评价 */
export function getSentEvaluations(params) {
  return request.get('/evaluation/sent', { params })
}

/** 某个资源下的评价(公开) */
export function getEvaluationsByResource(resourceId, params) {
  return request.get(`/evaluation/resource/${resourceId}`, { params })
}

/** 某笔订单的评价情况 */
export function getEvaluationsByOrder(orderId) {
  return request.get(`/evaluation/order/${orderId}`)
}

// ── 后台 ──────────────────────────────────────────────────────

/** 后台评价列表(含被隐藏的,受数据级权限约束) */
export function getAdminEvaluations(params) {
  return request.get('/admin/evaluation/list', { params })
}

/** 隐藏违规评价 */
export function hideEvaluation(id) {
  return request.put(`/admin/evaluation/${id}/hide`)
}

/** 恢复被隐藏的评价 */
export function showEvaluation(id) {
  return request.put(`/admin/evaluation/${id}/show`)
}
