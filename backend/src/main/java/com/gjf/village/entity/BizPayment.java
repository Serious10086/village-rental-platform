package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付流水表。
 *
 * <p><b>为什么资金记录要单独一张表,而不是在订单上塞几个金额字段:</b>
 * 一笔订单会产生不止一条资金记录 —— 买家付款是一条,卖家货款结算是一条,
 * 押金退还又是一条。它们是三笔独立的资金流动,发生在不同时刻、
 * 流向不同的人。压在订单表里就得加"押金退还时间""结算金额"等一堆列,
 * 且一旦将来出现部分退款、二次结算就彻底塞不下了。
 *
 * <p>这是"订单-支付"一对多的标准建模。表名叫"流水"也是这个意思:
 * 它是一本账,只增不改,每笔钱都能追到来源。
 */
@Data
@TableName("biz_payment")
public class BizPayment implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 类型:微信(模拟) —— 买家付款 */
    public static final int TYPE_WECHAT = 1;
    /** 类型:支付宝(模拟) */
    public static final int TYPE_ALIPAY = 2;
    /** 类型:押金退还 —— 流向买家 */
    public static final int TYPE_DEPOSIT_REFUND = 3;
    /** 类型:货款结算 —— 流向卖家 */
    public static final int TYPE_SETTLEMENT = 4;
    /** 类型:提现打款 */
    public static final int TYPE_WITHDRAW = 5;

    /** 状态:待支付 */
    public static final int STATUS_PENDING = 0;
    /** 状态:成功 */
    public static final int STATUS_SUCCESS = 1;
    /** 状态:失败 */
    public static final int STATUS_FAILED = 2;
    /** 状态:已退款 */
    public static final int STATUS_REFUNDED = 3;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 支付流水号,如 PAY20260917000001 */
    private String payNo;

    /** 关联订单ID;提现类流水为 NULL */
    private Long orderId;

    /** 付款/收款人ID —— 同一条流水既有"谁付的"也有"谁收的",靠 payType 区分 */
    private Long userId;

    private Integer payType;

    private BigDecimal amount;

    private Integer payStatus;

    /** 模拟第三方流水号 */
    private String channelTradeNo;

    private LocalDateTime payTime;

    private LocalDateTime refundTime;

    private String remark;

    /** 村组ID —— 数据级权限锚点 */
    private Long villageId;

    /** 本表没有 update_time:流水是不可变的账目记录,写入后不再修改 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
