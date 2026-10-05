package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.ResultCode;
import com.gjf.village.common.util.SensitiveWordUtil;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.dto.ChatQueryDTO;
import com.gjf.village.dto.ChatSendDTO;
import com.gjf.village.dto.ChatStartDTO;
import com.gjf.village.entity.BizOrder;
import com.gjf.village.entity.BizResource;
import com.gjf.village.entity.MsgChatMessage;
import com.gjf.village.entity.MsgConversation;
import com.gjf.village.entity.SysUser;
import com.gjf.village.mapper.BizOrderMapper;
import com.gjf.village.mapper.BizResourceMapper;
import com.gjf.village.mapper.MsgChatMessageMapper;
import com.gjf.village.mapper.MsgConversationMapper;
import com.gjf.village.mapper.SysUserMapper;
import com.gjf.village.service.ChatService;
import com.gjf.village.service.DataScopeHelper;
import com.gjf.village.vo.ChatMessageVO;
import com.gjf.village.vo.ConversationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 站内信服务实现。
 *
 * <p>这个类里有两个地方值得单独讲,都是"看起来小题大做、实际不做会出事"的:
 * <ol>
 *   <li><b>会话双方必须按大小归一化</b>(见 {@link #startConversation})</li>
 *   <li><b>未读数必须在数据库端自增</b>(见 {@link #send})</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final MsgConversationMapper conversationMapper;
    private final MsgChatMessageMapper chatMessageMapper;
    private final SysUserMapper userMapper;
    private final BizResourceMapper resourceMapper;
    private final BizOrderMapper orderMapper;
    private final SensitiveWordUtil sensitiveWordUtil;
    private final DataScopeHelper dataScopeHelper;

    /** 会话列表里"最后一条消息"的预览长度,与 {@code msg_conversation.last_message} 的 VARCHAR(200) 对齐 */
    private static final int PREVIEW_LENGTH = 200;

    /** 一次最多往回取多少条消息 */
    private static final int MAX_MESSAGE_LIMIT = 200;

    private static final int DEFAULT_MESSAGE_LIMIT = 50;

    // ═══════════════════════════════════════════════════════════
    //  发起会话
    // ═══════════════════════════════════════════════════════════

    /**
     * 发起会话,已存在则直接返回。
     *
     * <p><b>这里最关键的一步是把两个用户 ID 排序:</b>
     * 约定 {@code user_a_id} 存小的、{@code user_b_id} 存大的。
     * 不这么做的话,"我点他"生成 (1,2),"他点我"生成 (2,1),
     * 数据库里的 {@code uk_conv(user_a_id, user_b_id, resource_id)}
     * 会把这两条当成不同的会话 —— 唯一约束完全失效,双方各说各话,
     * 会话列表里一个人看到的是自己发出去的消息,另一个人看到的是自己的,
     * 互相都收不到。
     *
     * <p>排序这个动作必须在<b>写入之前</b>做,而且必须只在这一处做。
     * 一旦某条路径漏了,数据就歪了,且不会报错 —— 只会在用户投诉
     * "我发的消息对方看不到"时才被发现。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public Long startConversation(ChatStartDTO dto) {
        Long me = UserContext.getUserId();
        Long target = dto.getTargetUserId();

        if (Objects.equals(me, target)) {
            throw BusinessException.badRequest("不能和自己发起会话");
        }

        // 对方必须真实存在 —— 外键也会拦,但那报的是数据库异常,
        // 用户看到的是"系统繁忙",不如这里给一句人话
        SysUser peer = userMapper.selectById(target);
        if (peer == null) {
            throw BusinessException.notFound("对方用户不存在");
        }

        // 资源存在性同理。这里不做"资源必须属于对方"的强校验:
        // 会话是两个人的事,资源只是话题标签,认错人比报错更轻
        if (dto.getResourceId() != null && resourceMapper.selectById(dto.getResourceId()) == null) {
            throw BusinessException.notFound("关联的资源不存在");
        }
        if (dto.getOrderId() != null && orderMapper.selectById(dto.getOrderId()) == null) {
            throw BusinessException.notFound("关联的订单不存在");
        }

        // ── 归一化:小的放 a,大的放 b ──────────────────────────
        Long a = Math.min(me, target);
        Long b = Math.max(me, target);

        MsgConversation exists = conversationMapper.selectByUsersAndResource(
                a, b, dto.getResourceId());
        if (exists != null) {
            return exists.getId();
        }

        MsgConversation conversation = new MsgConversation();
        conversation.setUserAId(a);
        conversation.setUserBId(b);
        conversation.setResourceId(dto.getResourceId());
        conversation.setOrderId(dto.getOrderId());
        conversation.setAUnread(0);
        conversation.setBUnread(0);
        conversationMapper.insert(conversation);

        log.info("发起会话: convId={}, a={}, b={}, resourceId={}, orderId={}",
                conversation.getId(), a, b, dto.getResourceId(), dto.getOrderId());
        return conversation.getId();
    }

    // ═══════════════════════════════════════════════════════════
    //  发送消息
    // ═══════════════════════════════════════════════════════════

    /**
     * 发一条消息。
     *
     * <p>四件事必须一起完成:写消息、更会话的最后消息预览、给对方的未读数 +1、
     * 否则会出现"消息发出去了但对方没有红点"或"红点有了但列表里看不到内容"。
     * 所以整个方法是一个事务。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void send(ChatSendDTO dto) {
        Long me = UserContext.getUserId();
        MsgConversation conversation = requireConversation(dto.getConversationId(), me);
        Long toUserId = conversation.peerOf(me);

        // ── 敏感词:两级处理,与资源发布、评价完全一致 ──────────
        //
        // 拦截类:整条拒绝,不写库。这类词的危害是"说出去了就收不回",
        //         打码放行等于让它换一种形式传播。
        // 替换类:打码后放行,但<b>库里存原文</b>并打上 is_filtered 标记。
        //         原文是纠纷调解的证据;前台读取时统一过滤。
        String content = dto.getContent();
        if (sensitiveWordUtil.shouldBlock(content)) {
            Set<String> hits = sensitiveWordUtil.findAll(content);
            throw new BusinessException(ResultCode.SENSITIVE_CONTENT,
                    "消息包含违规词,无法发送:" + String.join("、", hits));
        }
        boolean filtered = !sensitiveWordUtil.filter(content).equals(content);

        MsgChatMessage message = new MsgChatMessage();
        message.setConversationId(conversation.getId());
        message.setFromUserId(me);
        message.setToUserId(toUserId);
        message.setContent(content);
        message.setMsgType(MsgChatMessage.TYPE_TEXT);
        message.setIsRead(0);
        message.setIsFiltered(filtered ? 1 : 0);
        chatMessageMapper.insert(message);

        // ── 更新会话冗余列 ──────────────────────────────────────
        //
        // 更新用"只改这几列"的定向更新,不用 updateById 传整个对象:
        // 那样会把读到的 a_unread/b_unread 一起写回去,和下面的自增打架。
        MsgConversation touch = new MsgConversation();
        touch.setId(conversation.getId());
        // 预览存打码后的文本 —— 列表里露出的那一眼也该是干净的
        touch.setLastMessage(abbreviate(sensitiveWordUtil.filter(content)));
        touch.setLastTime(LocalDateTime.now());
        conversationMapper.updateById(touch);

        // ── 对方未读 +1,必须在数据库端做 ───────────────────────
        //
        // 写成"查出来 +1 再写回"的话,同一会话连发两条消息时,
        // 两次都读到同一个旧值(比如 0),各自写回 1,未读数只加了 1。
        // 这和 SysUserMapper.increaseBalance 是同一类问题:并发下的丢失更新。
        if (Objects.equals(conversation.getUserAId(), toUserId)) {
            conversationMapper.increaseAUnread(conversation.getId());
        } else {
            conversationMapper.increaseBUnread(conversation.getId());
        }

        log.info("发送消息: convId={}, from={}, to={}, filtered={}",
                conversation.getId(), me, toUserId, filtered);
    }

    // ═══════════════════════════════════════════════════════════
    //  查询
    // ═══════════════════════════════════════════════════════════

    @Override
    public List<ConversationVO> listMyConversations() {
        return conversationMapper.selectMyConversations(UserContext.getUserId());
    }

    /**
     * 最近若干条消息,按时间正序返回。
     *
     * <p>SQL 里是 {@code ORDER BY id DESC LIMIT n}(取最新的 n 条),
     * 这里再翻一次变成正序。多这一次翻转是必要的:聊天窗口从上往下读,
     * 最新的在底部。如果 SQL 写成正序 + LIMIT,消息超过 n 条之后
     * 打开会话看到的就成了很久以前的开场白 —— 而且这个 bug
     * 在消息少于 n 条的测试数据下永远复现不出来。
     */
    @Override
    public List<ChatMessageVO> listRecentMessages(Long conversationId, Integer limit) {
        Long me = UserContext.getUserId();
        requireConversation(conversationId, me);

        int size = (limit == null || limit < 1) ? DEFAULT_MESSAGE_LIMIT
                : Math.min(limit, MAX_MESSAGE_LIMIT);

        List<MsgChatMessage> rows = chatMessageMapper.selectRecent(conversationId, size);
        Collections.reverse(rows);

        List<ChatMessageVO> result = new ArrayList<>(rows.size());
        for (MsgChatMessage row : rows) {
            result.add(toVO(row, me, true));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void markRead(Long conversationId) {
        Long me = UserContext.getUserId();
        MsgConversation conversation = requireConversation(conversationId, me);

        // 两张表都要动:消息表记"这条已读",会话表记"我还有几条没读"。
        // 只改一张的话,要么列表上的红点消不掉,要么下次进来又冒出来。
        chatMessageMapper.markRead(conversationId, me);
        if (Objects.equals(conversation.getUserAId(), me)) {
            conversationMapper.clearAUnread(conversationId);
        } else {
            conversationMapper.clearBUnread(conversationId);
        }
    }

    @Override
    public int countMyUnread() {
        return conversationMapper.sumMyUnread(UserContext.getUserId());
    }

    /**
     * 按订单调取聊天记录(后台纠纷处理用)。
     *
     * <p>不按会话分组地把消息铺开,是为了让管理员直接按时间线读完整段对话 ——
     * "他先说要退,对方说过两天"这种先后关系一旦被会话切断就看不出来了。
     * 每条消息带 {@code conversationId},{@code isFiltered} 标记也在,
     * 需要时前端可以按会话折叠。
     */
    @Override
    public PageResult<ChatMessageVO> pageOrderMessages(ChatQueryDTO query) {
        if (query.getOrderId() == null) {
            throw BusinessException.badRequest("请指定订单");
        }

        // 先把"未登录"这一关单独走掉。
        // canManageVillage 内部用的是 getOrNull,匿名访问时它只返回 false,
        // 于是请求落到下面那行 403「只能查看本村订单的聊天记录」——
        // 一个 token 过期的人看到这句话,完全不知道该去重新登录。
        // 401 和 403 是两件事:前者是"还不知道你是谁",后者是"你不行"。
        UserContext.get();

        // 纠纷是"某个村的交易纠纷",所以要过数据级权限 ——
        // 不过的话,村管改一下 URL 里的订单ID就能读到别村两人的私聊。
        BizOrder order = orderMapper.selectById(query.getOrderId());
        if (order == null) {
            throw BusinessException.notFound("订单不存在");
        }
        if (!dataScopeHelper.canManageVillage(order.getVillageId())) {
            throw BusinessException.forbidden("只能查看本村订单的聊天记录");
        }

        // 会话维度不参与过滤(要按订单看全部),清掉防误用
        query.setConversationId(null);

        Page<ChatMessageVO> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<ChatMessageVO> result = chatMessageMapper.selectMessagePage(page, query);
        // 后台看原文:tag=false 表示"不打码"
        result.getRecords().forEach(vo -> maskContent(vo, false));
        return PageResult.of(result);
    }

    // ═══════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════

    /**
     * 取出会话并校验我确实是其中一方。
     *
     * <p>会话ID是可以被猜到的(自增主键),所以每一个用到会话ID的接口
     * 都必须过这一关。漏掉任何一处,换个ID就能读到别人的私聊。
     */
    private MsgConversation requireConversation(Long conversationId, Long me) {
        MsgConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw BusinessException.notFound("会话不存在");
        }
        if (!conversation.contains(me)) {
            throw BusinessException.forbidden("你不在这个会话里");
        }
        return conversation;
    }

    private ChatMessageVO toVO(MsgChatMessage message, Long me, boolean mask) {
        ChatMessageVO vo = new ChatMessageVO();
        vo.setId(message.getId());
        vo.setConversationId(message.getConversationId());
        vo.setFromUserId(message.getFromUserId());
        vo.setToUserId(message.getToUserId());
        vo.setContent(message.getContent());
        vo.setMsgType(message.getMsgType());
        vo.setIsRead(message.getIsRead());
        vo.setIsFiltered(message.getIsFiltered());
        vo.setIsMine(Objects.equals(message.getFromUserId(), me));
        vo.setCreateTime(message.getCreateTime());
        maskContent(vo, mask);
        return vo;
    }

    /**
     * 按需打码。
     *
     * <p>只有 {@code isFiltered = 1} 才需要处理 —— 绝大多数消息没命中过敏感词,
     * 对每一条都跑一遍 DFA 是白费。这个标记的存在意义正在于此:
     * 它把"要不要过滤"从"每次都判断"变成了"查一次标记"。
     */
    private void maskContent(ChatMessageVO vo, boolean mask) {
        if (!mask || !Integer.valueOf(1).equals(vo.getIsFiltered())) {
            return;
        }
        vo.setContent(sensitiveWordUtil.filter(vo.getContent()));
    }

    private String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= PREVIEW_LENGTH ? text : text.substring(0, PREVIEW_LENGTH);
    }
}
