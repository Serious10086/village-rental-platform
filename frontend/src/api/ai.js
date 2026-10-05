import request from './request'

/**
 * AI 智能客服接口。
 *
 * 【两个都不能免登录】
 * 公告、留言板能在登录前看,这两个不行 —— 对话记录要挂在具体的人身上
 * (外键指着 sys_user)、提示词里要带上用户所在村组,而且这是按量计费的
 * 外部服务,开放给游客等于把账单交给所有人。
 */

// ── 前台 ──────────────────────────────────────────────────────

/**
 * 问一句。
 *
 * 【这个接口不会因为 AI 挂了而失败】
 * 返回体的 status 有三种取值,answer 一定有值:
 *   1 成功 —— AI 的回答
 *   2 降级 —— 本地兜底话术,原因在 degradeReason
 *   3 失败 —— 提问被内容安全拦下
 * 所以调用方不要写「catch 到错误就显示客服不可用」,而要看 status。
 */
export function askAi(data) {
  return request.post('/ai/chat', data)
}

/** 标记某轮回答「已解决」 */
export function markAiResolved(recordId) {
  return request.put(`/ai/${recordId}/resolved`)
}

// ── 后台 ──────────────────────────────────────────────────────

/**
 * 会话记录列表。
 *
 * 与前台那条的根本差别:这里不过滤 user_id,而是按**数据级权限**过滤 ——
 * 村管只看本村,超管看全部。前端不传村组参数,传了也不生效
 * (DTO 里刻意没有那个字段)。
 */
export function getAdminAiRecords(params) {
  return request.get('/admin/ai/list', { params })
}

/**
 * 降级率与原因分布。
 *
 * 降级率的分母是**全部记录**,失败(内容拦截)单独计数、不计入降级率 ——
 * 拦截是内容安全在正常工作,不是 AI 服务出了问题。
 */
export function getAdminAiStat(params) {
  return request.get('/admin/ai/stat', { params })
}

/**
 * 标记为需要人工跟进。
 *
 * 注意它**不会转接给谁、也不会通知谁** —— 系统里没有在线的人工客服。
 * 它只是给管理员一个待办标记,后台据此筛出"AI 没答好、需要有人跟一下"的记录。
 */
export function markAiFollowUp(id) {
  return request.put(`/admin/ai/${id}/follow-up`)
}
