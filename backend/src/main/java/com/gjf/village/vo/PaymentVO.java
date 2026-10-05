package com.gjf.village.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资金流水(钱包的"收益明细")。
 *
 * <p>{@code direction} 是这里最关键的一列。{@code biz_payment} 一张表
 * 同时记着买家付款(出账)、卖家结算(入账)、押金退还(入账)、
 * 提现(出账)四种语义完全相反的动作。前端要按"进"和"出"分色显示,
 * 如果让每个页面自己按 {@code payType} 去判断方向,那么新增一种
 * 支付方式时必然有一处漏改,表现是金额颜色对了但加减号反了 ——
 * 这种错在小额测试里根本看不出来。
 *
 * <p>所以方向由服务端在 {@code BizPayment.TYPE_*} 上定义一次,
 * 各处只管展示。
 */
@Data
public class PaymentVO {

    private Long id;

    private String payNo;

    private Long orderId;

    private String orderNo;

    /** 关联资源的名称,让"这笔钱是什么交易的"一眼可见 */
    private String resourceName;

    /** 1微信支付 2支付宝 3押金退还 4卖家结算 5提现 */
    private Integer payType;

    private String payTypeName;

    /**
     * 资金方向:1 入账 / -1 出账。
     *
     * <p>用整数而不是布尔,是因为将来可能有更多方向语义;
     * 用整数而不是字符串,是因为前端要拿它做正负号和配色。
     */
    private Integer direction;

    private BigDecimal amount;

    private Integer payStatus;

    private String payStatusName;

    private LocalDateTime payTime;

    private String remark;

    private LocalDateTime createTime;
}
