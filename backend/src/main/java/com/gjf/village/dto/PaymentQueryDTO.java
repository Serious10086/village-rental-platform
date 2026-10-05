package com.gjf.village.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 资金流水查询条件("我的钱包 - 收益明细")。
 *
 * <p>只开放两个筛选维度:按业务类型、按订单。不开放时间区间,
 * 是因为流水页面已经按时间倒序分页,加时间筛选的边际价值很低,
 * 却要多写一套日期区间校验和索引优化(YAGNI)。
 *
 * <p>{@code userId} <b>由 Service 强制写成当前登录用户</b>,
 * 不接受前端传入 —— 资金流水里有金额和订单号,能看到别人的
 * 就等于看到了别人在平台上做了多少生意。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PaymentQueryDTO extends BaseQueryDTO {

    /** 按流水人筛选,前台由 Service 强制写入 */
    private Long userId;

    /** 1微信支付 2支付宝 3押金退还 4卖家结算 5提现 */
    private Integer payType;

    private Long orderId;
}
