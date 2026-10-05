import request from './request'

/**
 * 站内信 / 聊天接口。
 *
 * 【会话从哪来】
 * 用户永远不"新建会话" —— 都是点「联系卖家」或「联系对方」时调用 start,
 * 服务端按 (双方, 资源) 找到已有会话就复用,没有才建一条。
 * 这样同一个人围绕同一件东西反复聊,始终只有一个会话。
 *
 * 【未读数为什么不在这里算】
 * 会话列表接口直接带回每条会话的 unread,不提供"未读数 = 消息总数 - 已读数"
 * 这种让前端自己减的写法。理由和订单列表的 actions 一样:
 * 一套计数规则只应该有一处定义,前端再算一遍迟早和后端对不上。
 */

/** 打开/复用与某人的会话。resourceId、orderId 可空 —— 都为空就是纯私聊 */
export function startChat(data) {
  return request.post('/chat/start', data)
}

/** 发消息 */
export function sendMessage(data) {
  return request.post('/chat/send', data)
}

/** 我的会话列表(含未读数,服务端已按最后发言时间倒序) */
export function getConversations() {
  return request.get('/chat/conversations')
}

/** 某个会话的消息。limit 不传则取默认条数(倒序取最近若干条,返回时已翻正) */
export function getMessages(conversationId, limit) {
  return request.get('/chat/messages', { params: { conversationId, limit } })
}

/** 标记会话已读 */
export function markConversationRead(conversationId) {
  return request.put(`/chat/${conversationId}/read`)
}

/** 未读总数(顶部消息红点用) */
export function getUnreadCount() {
  return request.get('/chat/unread')
}

/**
 * 按订单调取聊天记录 —— 后台纠纷抽屉专用。
 *
 * 聊天消息默认只对会话双方可见,这是唯一的例外:
 * 管理员调解纠纷时需要看到双方到底说了什么。
 * 服务端在 Service 层校验"你有没有权管这个村",传个别人的 orderId 是拿不到数据的。
 */
export function getOrderMessages(params) {
  return request.get('/chat/order-messages', { params })
}
