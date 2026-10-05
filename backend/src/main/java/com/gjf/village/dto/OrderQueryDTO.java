package com.gjf.village.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 订单列表查询条件。
 *
 * <p>继承 {@link BaseQueryDTO} 拿到分页参数与数据级权限字段。
 *
 * <p>注意 {@code buyerId} / {@code sellerId} 这两个"我是哪一方"的字段
 * <b>不由前端传入</b> —— 前台"我买到的""我卖出的"两个列表由 Service
 * 强制写入当前用户ID。如果让前端传,改一下就能看到别人的订单。
 * 后台管理列表则两个都不设,改由 {@code villageIds} 做村组过滤。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderQueryDTO extends BaseQueryDTO {

    /** 订单状态,见 OrderStatusEnum */
    private Integer orderStatus;

    /** 订单号(模糊匹配,用于"按订单号查单") */
    private String orderNo;

    /** 订单类型:1租赁 2购买 */
    private Integer orderType;

    /** 买家ID —— 由 Service 注入,不接受前端传入 */
    private Long buyerId;

    /** 卖家ID —— 由 Service 注入,不接受前端传入 */
    private Long sellerId;
}
