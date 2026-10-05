package com.gjf.village.service;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.dto.ChatQueryDTO;
import com.gjf.village.dto.ChatSendDTO;
import com.gjf.village.dto.ChatStartDTO;
import com.gjf.village.vo.ChatMessageVO;
import com.gjf.village.vo.ConversationVO;

import java.util.List;

/**
 * 站内信服务 —— 会话与消息。
 *
 * <p>业务侧只负责"沟通",不负责"成交"。所有涉及钱的判断都在订单模块里,
 * 聊天里说的话不构成任何交易承诺 —— 这一点在答辩时值得说一句:
 * 平台把<b>沟通</b>和<b>交易</b>分开,是因为聊天内容无法作为契约凭据,
 * 只有订单才是。聊天记录的价值在纠纷时<b>作为佐证</b>,而不是作为依据本身。
 */
public interface ChatService {

    /**
     * 发起会话(幂等)。
     *
     * <p>同一对用户、同一个资源,反复点"联系卖家"只会得到同一个会话ID。
     * 前端不用判断"是新建还是打开",直接拿返回的 ID 进聊天页。
     *
     * @return 会话ID
     */
    Long startConversation(ChatStartDTO dto);

    /** 在当前会话里发一条消息 */
    void send(ChatSendDTO dto);

    /** 我的会话列表(按最后消息时间倒序,未读在前排) */
    List<ConversationVO> listMyConversations();

    /**
     * 某个会话最近的消息。
     *
     * <p>取的是<b>最新的</b> N 条而不是第一页 —— 打开聊天窗口看到的
     * 应该是刚聊完的那几句,不是一个月前的开场白。
     */
    List<ChatMessageVO> listRecentMessages(Long conversationId, Integer limit);

    /** 把这个会话里对方发给我的消息全部标为已读 */
    void markRead(Long conversationId);

    /** 我的聊天未读总数(导航红点用) */
    int countMyUnread();

    /**
     * 按订单调取聊天记录 —— 后台处理纠纷的依据之一。
     *
     * <p>与前台接口的关键差别:<b>返回原文,不做打码</b>。
     * 管理员要判断"是谁先挑起的",看到的必须是双方实际说的话,
     * 打码后"我***你"这种内容既判不了是非,也认不出责任方。
     * 每条消息带 {@code isFiltered} 标记,让管理员知道哪些内容
     * 当时被过滤过、用户看到的和这里的不一样。
     */
    PageResult<ChatMessageVO> pageOrderMessages(ChatQueryDTO query);
}
