package com.gjf.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 发送聊天消息。
 *
 * <p>{@code conversationId} 而不是 {@code toUserId} —— 消息必须落在一条
 * 已存在的会话里。如果接口收的是接收人ID,那么每次发消息都要再推一遍
 * "我是谁、发给谁",而这恰恰是 {@code uk_conv} 想约束的那两个字段;
 * 绕开会话表发消息,会话的未读数和最后一条消息预览就永远不会更新。
 */
@Data
public class ChatSendDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "缺少会话ID")
    private Long conversationId;

    /**
     * 消息正文。
     *
     * <p>上限 1000 字与 {@code msg_chat_message.content} 的
     * {@code VARCHAR(1000)} 对齐。校验写在这里而不是靠数据库截断 ——
     * MySQL 严格模式下超长是直接报错,错误信息对用户毫无意义。
     */
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 1000, message = "单条消息最多 1000 字")
    private String content;
}
