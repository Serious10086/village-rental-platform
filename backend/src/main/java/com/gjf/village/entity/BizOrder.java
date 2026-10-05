package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 订单表。
 *
 * <p>本表是交易域的主干,资金流水({@link BizPayment})、信用变更
 * ({@link BizCreditRecord})、评价、纠纷、聊天记录全部挂在它上面。
 *
 * <p><b>两条字段设计上必须守住的线:</b>
 * <ul>
 *   <li>{@code unitPrice} 是<b>快照</b> —— 村民事后调价不能改动历史订单金额。
 *       这是订单表的铁律:合同一旦成立,价格就固定了。</li>
 *   <li>{@code payAmount} 是<b>冗余</b>的(等于 totalAmount + depositAmount)。
 *       多存一份是有意的:押金参与结算、租金参与结算,但两者归属不同
 *       (押金最终退买家、租金归卖家)。拆成三列后,每次结算不用重算,
 *       对账时也能一眼看出这笔钱里多少是押金。</li>
 * </ul>
 */
@Data
@TableName("biz_order")
public class BizOrder implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 订单类型:租赁 */
    public static final int TYPE_RENT = 1;
    /** 订单类型:购买 */
    public static final int TYPE_BUY = 2;

    /** 支付状态:未支付 */
    public static final int PAY_UNPAID = 0;
    /** 支付状态:已支付 */
    public static final int PAY_PAID = 1;
    /** 支付状态:已退款 */
    public static final int PAY_REFUNDED = 2;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 订单号,如 LO20260917000001 */
    private String orderNo;

    /** 订单类型:1租赁 2购买(由资源的 trade_type 决定,不接受前端传入) */
    private Integer orderType;

    private Long resourceId;

    /** 租户/买家ID */
    private Long buyerId;

    /** 发布村民ID */
    private Long sellerId;

    /** 交易发生村组ID —— 数据级权限锚点 */
    private Long villageId;

    /** 租期开始日(购买类为 NULL) */
    private LocalDate rentStartDate;

    /** 租期结束日 */
    private LocalDate rentEndDate;

    /** 租期天数(含头含尾) */
    private Integer rentDays;

    /** 成交单价快照 */
    private BigDecimal unitPrice;

    /** 租金/货款总额 */
    private BigDecimal totalAmount;

    /** 押金 */
    private BigDecimal depositAmount;

    /** 应付总额 = totalAmount + depositAmount */
    private BigDecimal payAmount;

    /** 订单状态,取值见 {@code OrderStatusEnum} */
    private Integer orderStatus;

    /** 支付状态:0未支付 1已支付 2已退款 */
    private Integer payStatus;

    private Integer deliveryType;

    /** 高风险资源的安全培训已确认 */
    private Integer trainingConfirmed;

    private String buyerRemark;

    private String cancelReason;

    // ── 时间轴字段 ──────────────────────────────────────────────
    //
    // 刻意用五个独立时刻而不是"一条状态变更日志表":
    // 订单的时间轴是固定的五步(确认→支付→交付→归还→完成),
    // 用列表达既省一张表,又能直接用 SQL 算出"平均接单时长"这类指标。
    // 如果各状态流转是无序的、可重复的,才需要单独的日志表。

    private LocalDateTime confirmTime;
    private LocalDateTime payTime;
    private LocalDateTime deliverTime;
    private LocalDateTime returnTime;
    private LocalDateTime finishTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
