package com.gjf.village.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 取消 / 拒绝订单的请求体。
 *
 * <p>原因字段在拒绝和强制取消时是<b>必填</b>(由 Service 校验)——
 * 卖家只回一句"拒绝"而不说理由,买家不知道该改什么,平台也失去了
 * 一次发现问题的机会。管理员强制取消更是如此:双方都会收到
 * 这条说明,没有它两边只会看到订单凭空消失。
 */
@Data
public class OrderCancelDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 取消/拒绝原因 */
    private String reason;
}
