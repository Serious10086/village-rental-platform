package com.gjf.village.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 发起会话请求(点"联系卖家"/"联系对方"时调用)。
 *
 * <p>三个定位字段里只有 {@code targetUserId} 是必填的:
 * 从资源详情页点进来会带上 {@code resourceId},从订单详情页点进来会带上
 * {@code orderId},两者都没有时就是一个纯私聊会话。
 *
 * <p><b>会话双方不从前端传。</b>前端只说自己想找谁,另一头是当前登录用户 ——
 * 让前端传 "我是谁" 等于允许任何人以别人的名义发起会话。
 */
@Data
public class ChatStartDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "请指定聊天对象")
    private Long targetUserId;

    /** 从哪个资源发起的,可为空 */
    private Long resourceId;

    /** 从哪笔订单发起的,可为空 */
    private Long orderId;
}
