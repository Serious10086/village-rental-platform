package com.gjf.village.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表项。
 *
 * <p>{@code peerName} 由 SQL 直接算好,不在 Java 里逐条补 —— 见
 * {@code MsgConversationMapper.xml} 的说明。
 */
@Data
public class ConversationVO {

    private Long id;

    /** 对方用户ID —— 前端发消息要用,但 <b>不由前端回传</b>,服务端会按会话重新推出 */
    private Long peerId;

    private String peerName;

    private String peerAvatar;

    private Long resourceId;

    /** 关联资源名称,可为空(不是针对资源发起的会话) */
    private String resourceTitle;

    private Long orderId;

    private String orderNo;

    /** 最后一条消息预览 */
    private String lastMessage;

    private LocalDateTime lastTime;

    /** 我的未读数 */
    private Integer unread;

    private LocalDateTime createTime;
}
