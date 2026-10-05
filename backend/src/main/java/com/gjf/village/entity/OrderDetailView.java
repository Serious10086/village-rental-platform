package com.gjf.village.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 订单详情视图实体,对应数据库视图 {@code v_order_detail}。
 *
 * <p>字段与视图列一一对应,所以这里<b>不能</b>加 {@code @TableName}/
 * {@code @TableId} 之类的 MyBatis-Plus 注解 —— 它不参与 CRUD,
 * 只承接自定义查询的结果映射。
 *
 * <p><b>这个视图刻意不带任何 WHERE 条件。</b>订单的可见范围因角色而异
 * (买家看自己的、卖家看自己收到的、村管看本村的、超管看全部),
 * 所以数据级过滤只能留到外层用 village_id / buyer_id / seller_id 兜住。
 * 与之相对,资源列表视图 {@code v_resource_list} 就把
 * "只看已上架"固化在了定义里 —— 因为那个条件对所有调用方都是一致的。
 *
 * <p>判断依据可以写进论文:<b>哪些条件能固化进视图、哪些必须留外层,
 * 取决于这个条件对所有调用方是否一致。</b>
 */
@Data
public class OrderDetailView implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String orderNo;
    private Integer orderType;

    private Long resourceId;
    private Long villageId;
    private Long buyerId;
    private Long sellerId;

    private LocalDate rentStartDate;
    private LocalDate rentEndDate;
    private Integer rentDays;

    private BigDecimal unitPrice;
    private BigDecimal totalAmount;
    private BigDecimal depositAmount;
    private BigDecimal payAmount;

    private Integer orderStatus;
    private Integer payStatus;
    private Integer deliveryType;
    private Integer trainingConfirmed;
    private String buyerRemark;
    private String cancelReason;

    private LocalDateTime confirmTime;
    private LocalDateTime payTime;
    private LocalDateTime deliverTime;
    private LocalDateTime returnTime;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;

    // ── 联出的资源信息 ──────────────────────────────────────────
    private String resourceName;
    private String coverUrl;
    private Integer tradeType;
    private String location;
    private String useRule;
    private Long publisherId;

    // ── 联出的买卖双方 ──────────────────────────────────────────
    private String buyerName;
    private String buyerPhone;
    private Integer buyerCredit;
    private String sellerName;
    private String sellerPhone;
    private Integer sellerCredit;

    private String villageName;
}
