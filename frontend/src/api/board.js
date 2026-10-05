import request from './request'

/**
 * 留言板接口。
 *
 * 【这里没有「传入我是谁」的参数】
 * 发帖、回复、点赞、删除都不传 userId —— 后端从 token 里取。
 * 一旦允许前端传,谁都能以别人的身份发帖。而列表接口里那个
 * userId 是**筛选条件**(只看某人发的),不是身份证明,两回事。
 *
 * 【读公开、写要登录】
 * 列表和回复在白名单里,游客也能看;发帖/点赞/删除不在,
 * 未登录调用会直接被拦截器挡回 401。前端据此在按钮上做提示,
 * 但真正的拦截在后端 —— 前端提示只是省一次往返。
 */

// ── 前台 ──────────────────────────────────────────────────────

/** 主帖列表(公开)。villageId 决定看哪个村的留言板 */
export function getBoardList(params) {
  return request.get('/board/list', { params })
}

/** 某条主帖下的回复(公开,按时间正序) */
export function getBoardReplies(parentId, params) {
  return request.get('/board/replies', { params: { parentId, ...params } })
}

/** 发主帖 */
export function postBoard(data) {
  return request.post('/board/post', data)
}

/** 回复某条主帖。只支持两级,parentId 必须指向主帖 */
export function replyBoard(data) {
  return request.post('/board/reply', data)
}

/** 点赞 / 取消点赞。同一个接口,返回 true=已赞 false=已取消 */
export function toggleBoardLike(id) {
  return request.post(`/board/${id}/like`)
}

/** 删除自己的留言 */
export function deleteBoard(id) {
  return request.delete(`/board/${id}`)
}

// ── 后台 ──────────────────────────────────────────────────────

/** 后台留言列表(含已隐藏的,受数据级权限约束) */
export function getAdminBoards(params) {
  return request.get('/admin/board/list', { params })
}

/** 管理员删除违规留言(逻辑删除,前台不再显示但库里可追溯) */
export function adminDeleteBoard(id) {
  return request.delete(`/admin/board/${id}`)
}
