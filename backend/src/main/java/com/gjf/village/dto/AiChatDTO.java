package com.gjf.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 向智能客服提问。
 *
 * <p>两个字段,但两个都有长度上限,而且上限<b>不是随便定的</b> ——
 * 它们和 {@code ai_chat_record} 表里那两列的定义一一对应
 * ({@code question VARCHAR(1000)}、{@code session_id VARCHAR(64)})。
 *
 * <p>在校验层挡住超长输入,是为了不把这个问题留给数据库:超出列宽时
 * MySQL 要么截断(于是存进去的问题和用户问的不是一句话),
 * 要么在严格模式下直接报错(于是用户看到一句"系统错误",
 * 而他只是问了一个长问题)。两种结果都比"提前告诉他太长了"差。
 */
@Data
public class AiChatDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "请先输入你的问题")
    @Size(max = 1000, message = "问题太长了,请精简到 1000 字以内")
    private String question;

    /**
     * 会话标识,前端生成。
     *
     * <p>服务端<b>不信任它的内容</b>,只用它把多轮对话串起来 ——
     * 取历史时还会再验一次归属人,详见 Mapper 里的注释。
     */
    @NotBlank(message = "会话标识不能为空")
    @Size(max = 64, message = "会话标识过长")
    private String sessionId;
}
