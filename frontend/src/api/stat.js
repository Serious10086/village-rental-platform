import request from './request'

/**
 * 数据统计接口。
 *
 * 【三个接口返回同一个形状】
 * 都是 `StatOverviewVO`(关键数字 + 几张图的数据),差别只在**范围**:
 * 工作台看本村或全平台、村级统计看一个村、全局统计看全部村。
 * 所以前端三页可以共用一套渲染逻辑,只是摆法不同。
 *
 * 【范围不在参数里】
 * 除了村级统计的 `villageId`(而且只有超管传了才算),范围一律由后端
 * 按登录态决定 —— 让前端传"我要看哪个村"的接口,改一下 URL 就能越权。
 */

/** 工作台:关键数字 + 分类分布 + 订单状态 + 近 30 天趋势 */
export function getDashboardStat() {
  return request.get('/admin/stat/dashboard')
}

/**
 * 村级统计。
 * @param villageId 只有超管需要传;村管传了也会被后端忽略
 */
export function getVillageStat(villageId) {
  return request.get('/admin/stat/village', { params: { villageId } })
}

/** 全局统计(仅超管):全平台数字 + 各村横向对比 */
export function getGlobalStat() {
  return request.get('/admin/stat/global')
}
