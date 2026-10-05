package com.gjf.village.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 聊天记录查询条件。
 *
 * <p>两个维度二选一,不会同时用:
 * <ul>
 *   <li>{@code conversationId} —— 聊天窗口里翻历史消息</li>
 *   <li>{@code orderId} —— 后台处理纠纷时调取这笔订单的全部聊天记录
 *       (见需求原文"调取订单和聊天记录作为依据")</li>
 * </ul>
 *
 * <p>{@code conversationId} 由 Service 校验归属后再写入,不接受前端
 * 直接传 —— 否则换个 ID 就能读到任意两个人的私聊。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ChatQueryDTO extends BaseQueryDTO {

    private Long conversationId;

    private Long orderId;
}
