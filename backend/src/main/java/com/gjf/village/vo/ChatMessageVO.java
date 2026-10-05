package com.gjf.village.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息。
 *
 * <p>{@code isMine} 是服务端算好的,不是前端拿 {@code fromUserId}
 * 跟自己的ID比出来的 —— 前端算也不难,但那样每条消息的展示逻辑
 * 就散在各个模板里,将来加"撤回""引用"时每个地方都要改。
 */
@Data
public class ChatMessageVO {

    private Long id;

    private Long conversationId;

    private Long fromUserId;

    private Long toUserId;

    /**
     * 消息内容。
     *
     * <p><b>数据库里存的是原文</b>,这一列在<b>前台</b>接口返回时已被打码。
     * 为什么会这样分:聊天记录是纠纷调解的证据,不能因为几个词就把整句
     * 上下文从库里抹掉 —— 那样管理员看到的是"我操***你",既判不了是非,
     * 也没法认定是谁先骂的。所以原文保留,展示层统一过滤;
     * 后台调取聊天记录时拿到的是<b>带 {@code isFiltered} 标记的原文</b>。
     *
     * <p>这和匿名评价用的是同一条原则:<b>保留事实、控制展示</b>。
     */
    private String content;

    /** 1文本 2图片 */
    private Integer msgType;

    /** 是否已读 */
    private Integer isRead;

    /**
     * 是否命中过敏感词。
     *
     * <p>前端据此在气泡旁加一个"内容已过滤"的提示。不提示的话,
     * 用户会以为对方打错字了,反而去追问 —— 把过滤这件事说破,
     * 比藏起来更不容易引起误会。
     */
    private Integer isFiltered;

    /** 是不是我发的 */
    private Boolean isMine;

    private LocalDateTime createTime;
}
