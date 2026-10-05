import request from './request'

/**
 * 公告接口。
 *
 * 【前台两个接口都在白名单里】
 * 公告要在<b>登录之前</b>就能看 —— 一个刚打开平台的访客先看到的是
 * 这个村在做什么,而不是一个登录框。所以列表和详情都免登录。
 *
 * 【"我能看哪些公告"不进参数】
 * 列表接口没有 villageId 参数。当前用户是哪个村的,由后端从 token 里取,
 * 未登录则只返回全平台公告。让前端传村组 id 的话,改一个数字就能
 * 看到别的村的公告 —— 而村级公告里是可能有内部通知的。
 */

// ── 前台 ──────────────────────────────────────────────────────

/** 公告列表(公开)。全平台公告 + 登录后附加本村公告,置顶优先 */
export function getAnnouncements(params) {
  return request.get('/announcement/list', { params })
}

/** 公告详情(公开)。带正文,并把浏览量 +1 */
export function getAnnouncementDetail(id) {
  return request.get(`/announcement/detail/${id}`)
}

// ── 后台 ──────────────────────────────────────────────────────

/**
 * 后台公告列表。
 *
 * 与前台的根本差别:这里带 status 参数,所以草稿和下架状态查得到 ——
 * 前台那条 SQL 把 status = 1 硬编码在里面,压根不看这个字段。
 */
export function getAdminAnnouncements(params) {
  return request.get('/admin/announcement/list', { params })
}

/**
 * 后台公告详情(带正文)。
 *
 * 与前台 /announcement/detail/{id} 的差别在状态:前台那个只认「已发布」,
 * 草稿和已下架的一律 404;后台这个不分状态,编辑时靠它取正文。
 */
export function getAdminAnnouncementDetail(id) {
  return request.get(`/admin/announcement/${id}`)
}

/**
 * 新建公告。
 *
 * `data.asDraft = true` 走「存草稿」:落成草稿态、不发通知、前台看不到;
 * 不传则直接发布并给能看到它的人发站内通知。
 */
export function publishAnnouncement(data) {
  return request.post('/admin/announcement/publish', data)
}

/**
 * 编辑公告内容。
 *
 * 只有这一条接口改内容 —— 归属(村组)和署名不在请求体里,
 * 改不了也不该改:公告挂在哪决定了谁能看见它。
 */
export function updateAnnouncement(id, data) {
  return request.put(`/admin/announcement/${id}`, data)
}

/** 发布已有的公告(草稿 / 已下架 → 已发布)。会重新盖发布时间并重发通知 */
export function publishExistingAnnouncement(id) {
  return request.put(`/admin/announcement/${id}/publish`)
}

/** 下架公告。前台立刻读不到,后台还能看到并再编辑 */
export function offlineAnnouncement(id) {
  return request.put(`/admin/announcement/${id}/offline`)
}
