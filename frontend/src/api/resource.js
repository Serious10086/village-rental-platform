import request from './request'

/**
 * 资源相关接口。
 */

/** 资源搜索(公开)。参数见后端 ResourceQueryDTO */
export function searchResources(params) {
  return request.get('/resource/list', { params })
}

/** 资源详情(公开) */
export function getResourceDetail(id) {
  return request.get(`/resource/detail/${id}`)
}

/** 发布资源 */
export function publishResource(data) {
  return request.post('/resource/publish', data)
}

/** 编辑资源 */
export function updateResource(data) {
  return request.put('/resource/update', data)
}

/** 我的发布 */
export function getMyResources(params) {
  return request.get('/resource/mine', { params })
}

/** 下架 */
export function offlineResource(id) {
  return request.put(`/resource/${id}/offline`)
}

/** 重新上架 */
export function onlineResource(id) {
  return request.put(`/resource/${id}/online`)
}

/** 调价 */
export function adjustPrice(id, price) {
  return request.put(`/resource/${id}/price`, null, { params: { price } })
}

/** 删除 */
export function deleteResource(id) {
  return request.delete(`/resource/${id}`)
}

// ── 分类 ──────────────────────────────────────────────────────

/** 分类树(公开) */
export function getCategoryTree() {
  return request.get('/category/tree')
}

/** 叶子分类列表(公开) */
export function getCategoryList() {
  return request.get('/category/list')
}

// ── 文件 ──────────────────────────────────────────────────────

/** 上传图片。用 FormData,不能走默认的 JSON 序列化 */
export function uploadImage(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/file/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

// ── 后台 ──────────────────────────────────────────────────────

/** 后台资源列表(受数据级权限约束) */
export function getAdminResources(params) {
  return request.get('/admin/resource/list', { params })
}
