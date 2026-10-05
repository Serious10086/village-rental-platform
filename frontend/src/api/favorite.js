import request from './request'

/**
 * 资源收藏接口。
 *
 * 两个都要登录:收藏是"我的东西",而且 `biz_resource_favorite.user_id`
 * 是指向 sys_user 的外键 —— 存不下匿名收藏。
 */

/**
 * 收藏 / 取消收藏(同一个接口,双向切换)。
 *
 * 返回值是**操作之后**的状态(true = 已收藏)。
 * 前端不要自己猜该显示实心还是空心星星 —— 猜的依据是"我以为的当前状态",
 * 而那个状态可能已经过期(比如在另一个标签页里取消过)。
 * 拿到服务端返回的值直接赋值,是唯一不会错的做法。
 */
export function toggleFavorite(resourceId) {
  return request.put(`/favorite/${resourceId}`)
}

/**
 * 我的收藏。
 *
 * 返回项里带资源的**当前状态**,所以已下架的收藏不会消失,
 * 而是显示成「已下架」—— 让用户知道东西还在、只是暂时不能租了。
 */
export function getMyFavorites(params) {
  return request.get('/favorite/mine', { params })
}
