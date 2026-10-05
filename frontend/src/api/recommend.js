import request from './request'

/**
 * 智能推荐接口。
 *
 * 【两个都是免登录的】
 * 首页和资源详情页本来就公开,推荐位要登录才显示会很别扭。
 * 游客拿到的是全平台热门 —— 没有行为可供个性化,这是事实而不是降级。
 */

// ── 推荐 ──────────────────────────────────────────────────────

/**
 * 首页「猜你喜欢」。
 *
 * 返回的每一项都带 `reason`(给用户看的推荐理由)和 `strategy`
 * (内容推荐 / 村组热度 / 全局兜底)。前端**要把 reason 显示出来** ——
 * 一排没有理由的推荐是黑箱,用户不知道它凭什么出现在那儿。
 */
export function getRecommendations(limit = 8) {
  return request.get('/recommend/me', { params: { limit } })
}

/** 详情页「相似资源」。同分类的其它已上架资源,不含当前这一条 */
export function getSimilarResources(resourceId, limit = 4) {
  return request.get(`/recommend/similar/${resourceId}`, { params: { limit } })
}

// ── 行为上报 ──────────────────────────────────────────────────

/**
 * 上报一次浏览(带停留时长和来源)。
 *
 * **silent: true** —— 这个请求不是用户主动发起的,失败了不该弹提示。
 * 用户只是点开一个资源看,后台顺手记一笔而已;为这一笔弹一句"操作失败",
 * 他根本不知道自己做了什么操作。旁路请求就该悄悄地失败。
 *
 * @param resourceId  看的哪个资源
 * @param staySeconds 停留了多少秒(前端自己算)
 * @param source      1列表页 2详情页 3搜索 4推荐位
 */
export function reportView(resourceId, staySeconds, source) {
  return request.post(
    '/behavior/view',
    { resourceId, staySeconds, source },
    { silent: true }
  )
}

// ── 后台 ──────────────────────────────────────────────────────

/**
 * 推荐位点击率(后台)。
 *
 * 返回的是「曝光 / 点击 / 点击率 / 按天明细」四个东西一起 ——
 * 界面必须把它们一起显示:只给点击数没有分母,那个数字读不出任何结论。
 */
export function getRecommendStat(params) {
  return request.get('/admin/recommend/stat', { params })
}
