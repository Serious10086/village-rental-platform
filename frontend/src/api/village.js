import request from './request'

/**
 * 村组管理接口(后台,仅超管)。
 *
 * 注意和 `@/api/auth` 里的 `getVillageTree()` 区分:
 * 那个走公开接口 `/village/tree`,只返回**启用中**的村组,给注册页的下拉用;
 * 这里走 `/system/village/*`,连停用的一起返回,给管理页用 ——
 * 停用的节点也得看得见,否则停用之后就再也启用不回来。
 */

/** 村组树(含停用节点),管理页表格用 */
export function getVillageManageTree() {
  return request.get('/system/village/list')
}

/** 新增村组,返回新ID */
export function createVillage(data) {
  return request.post('/system/village', data)
}

/** 修改村组 */
export function updateVillage(data) {
  return request.put('/system/village', data)
}

/** 删除村组。有下级或还有用户/资源挂着时后端会拒绝 */
export function deleteVillage(id) {
  return request.delete(`/system/village/${id}`)
}
