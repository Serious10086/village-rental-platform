import request from './request'

/**
 * 操作日志接口(后台)。
 *
 * 【只读】
 * 审计日志没有"编辑"和"删除"——能改的日志就不是审计。所以这里只有两个 GET。
 */

/** 操作日志列表。可按操作人、模块、状态、日期区间筛 */
export function getOperationLogs(params) {
  return request.get('/system/log/list', { params })
}

/** 日志里出现过的模块名,给下拉用 */
export function getLogModules() {
  return request.get('/system/log/modules')
}
