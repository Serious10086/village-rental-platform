import request from './request'

/**
 * 审核 / 通知 / 敏感词 相关接口。
 */

// ── 实名认证(前台) ───────────────────────────────────────────

/** 提交实名认证 */
export function submitRealname(data) {
  return request.post('/user/realname', data)
}

/** 获取个人信息 */
export function getProfile() {
  return request.get('/user/profile')
}

// ── 审核(后台) ───────────────────────────────────────────────

/** 实名审核列表 */
export function getRealnameAuditList(params) {
  return request.get('/admin/user/audit/list', { params })
}

/** 实名审核(通过/驳回) */
export function auditRealname(data) {
  return request.post('/admin/user/audit', data)
}

/** 资源审核列表 */
export function getResourceAuditList(params) {
  return request.get('/admin/resource/audit/list', { params })
}

/** 资源审核(通过/驳回) */
export function auditResource(data) {
  return request.post('/admin/resource/audit', data)
}

/**
 * 审核详情(实名、资源与外来用户备案共用)。
 *
 * 实名审核的完整身份证号只有这个接口返回 —— 列表接口是脱敏的。
 */
export function getAuditDetail(id) {
  return request.get(`/admin/audit/detail/${id}`)
}

/** 资源待审数量 */
export function getResourcePendingCount() {
  return request.get('/admin/resource/audit/pending-count')
}

// ── 外来用户备案(后台) ───────────────────────────────────────

/** 外来用户备案列表 */
export function getOutsideAuditList(params) {
  return request.get('/admin/user/outside/list', { params })
}

/** 登记外来用户(建账号 + 生成待审核备案单) */
export function createOutsideUser(data) {
  return request.post('/admin/user/outside', data)
}

/** 审核外来用户备案(通过/驳回) */
export function auditOutside(data) {
  return request.post('/admin/user/outside/audit', data)
}

// ── 通知 ──────────────────────────────────────────────────────

/** 通知列表 */
export function getNoticeList(params) {
  return request.get('/notice/list', { params })
}

/** 未读数量 */
export function getUnreadCount() {
  return request.get('/notice/unread')
}

/** 标记单条已读 */
export function markNoticeRead(id) {
  return request.put(`/notice/${id}/read`)
}

/** 全部标记已读 */
export function markAllNoticeRead() {
  return request.put('/notice/read-all')
}

/** 删除通知 */
export function deleteNotice(id) {
  return request.delete(`/notice/${id}`)
}

// ── 敏感词(超管) ─────────────────────────────────────────────

export function getSensitiveWordList(params) {
  return request.get('/system/sensitive/list', { params })
}

export function addSensitiveWord(data) {
  return request.post('/system/sensitive', data)
}

export function updateSensitiveWord(data) {
  return request.put('/system/sensitive', data)
}

export function deleteSensitiveWord(id) {
  return request.delete(`/system/sensitive/${id}`)
}

export function batchImportSensitiveWords(data) {
  return request.post('/system/sensitive/batch', data)
}

/** 试一试:返回是否拦截、命中了哪些词、打码后的文本 */
export function testSensitiveWords(text) {
  return request.get('/system/sensitive/test', { params: { text } })
}

export function getSensitiveWordSize() {
  return request.get('/system/sensitive/size')
}
