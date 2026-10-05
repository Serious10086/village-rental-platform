import request from './request'

/**
 * 认证相关接口。
 */

/** 登录 */
export function login(data) {
  return request.post('/auth/login', data)
}

/** 注册 */
export function register(data) {
  return request.post('/auth/register', data)
}

/** 退出登录 */
export function logout() {
  return request.post('/auth/logout')
}

/** 修改当前登录用户的密码(改谁由 token 决定,不传 userId) */
export function changePassword(data) {
  return request.post('/auth/changePassword', data)
}

/** 获取当前用户信息(含角色与权限码) */
export function getInfo() {
  return request.get('/auth/getInfo')
}

/** 获取当前用户的菜单路由树 */
export function getRouters() {
  return request.get('/auth/getRouters')
}

/** 村组列表(公开接口,注册页用) */
export function getVillageList() {
  return request.get('/village/list')
}

/** 村组树(公开接口) */
export function getVillageTree() {
  return request.get('/village/tree')
}
