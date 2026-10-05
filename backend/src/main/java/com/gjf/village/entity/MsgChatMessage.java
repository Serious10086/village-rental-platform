package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 聊天消息表。
 *
 * <p><b>消息命中敏感词只标记、不删除:</b>后台处理纠纷要"调取聊天记录作为依据",
 * 如果命中敏感词的消息被物理删掉,那段对话就出现了断裂 —— 而断裂的
 * 恰恰是双方情绪最激动、信息量最大的那几句。所以做法是原地打码,
 * 用 {@code is_filtered} 标记,让管理员知道"这里原本有词被处理过"。
 *
 * <p><b>这是"保留事实、控制展示"原则的又一处应用</b>(与匿名评价同理):
 * 数据库留下完整的原始事实,展示层决定给谁看什么。
 */
@Data
@TableName("msg_chat_message")
public class MsgChatMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final int TYPE_TEXT = 1;
    public static final int TYPE_IMAGE = 2;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private Long fromUserId;

    private Long toUserId;

    /** 消息正文(命中"替换类"敏感词的部分已打码) */
    private String content;

    /** 消息类型:1 文本 2 图片 */
    private Integer msgType;

    /** 接收方是否已读 */
    private Integer isRead;

    /** 是否命中过敏感词 —— 标记而不是删除,便于后台核查 */
    private Integer isFiltered;

    private LocalDateTime createTime;
}
