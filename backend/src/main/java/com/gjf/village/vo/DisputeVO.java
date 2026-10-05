package com.gjf.village.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 纠纷单。
 *
 * <p>这个 VO 里同时装了三层信息,对应后台处理抽屉从上到下三块:
 * <ol>
 *   <li><b>纠纷本身</b> —— 谁告谁、什么类型、诉求多少、怎么说的</li>
 *   <li><b>订单摘要</b> —— 管理员要判断"该不该退钱",必须先知道这笔
 *       订单原本是什么状态、金额多大。这些字段是从 biz_order 联出来的,
 *       不是冗余存了两份</li>
 *   <li><b>处理结果</b> —— 谁处理的、怎么判的</li>
 * </ol>
 *
 * <p>把订单摘要一起联出来,是为了让"处理纠纷"这一个动作只发一次请求。
 * 如果拆成"先查纠纷、再查订单、再查聊天记录"三次,
 * 管理员点开一条纠纷要等三个来回,而这三样东西是一起看的。
 */
@Data
public class DisputeVO {

    private Long id;

    private String disputeNo;

    // ── 纠纷本身 ────────────────────────────────────────────────

    private Long orderId;

    private Long applicantId;

    private String applicantName;

    private String applicantPhone;

    private Long respondentId;

    private String respondentName;

    private String respondentPhone;

    /** 1损坏赔偿 2押金/退款争议 3实物不符 4逾期未还 5其他 */
    private Integer disputeType;

    private String disputeTypeName;

    private String description;

    /** 证据图片,逗号分隔在库里,这里转成数组 */
    private List<String> evidenceImages;

    /** 诉求金额,可为空(有些纠纷只是要求对方履约,不涉及钱) */
    private BigDecimal applyAmount;

    // ── 订单摘要(处理纠纷时必须同屏看到) ───────────────────────

    private String orderNo;

    private Integer orderType;

    private String orderTypeName;

    private Integer orderStatus;

    private String orderStatusName;

    /**
     * 申请纠纷<b>之前</b>的订单状态。
     *
     * <p>申请纠纷会把订单状态改成 6,原状态就丢了。存这一列是为了
     * 调解结束后把订单放回它原本该在的位置 —— 从"待交付"退回"待交付",
     * 而不是一律退回某个写死的状态。
     */
    private Integer orderStatusBefore;

    private String orderStatusBeforeName;

    private BigDecimal payAmount;

    private String resourceName;

    private LocalDate rentStartDate;

    private LocalDate rentEndDate;

    // ── 处理结果 ────────────────────────────────────────────────

    /** 0待受理 1处理中 2已调解 3已关闭 */
    private Integer status;

    private String statusName;

    private Long handlerId;

    private String handlerName;

    private String handleResult;

    private LocalDateTime handleTime;

    private Long villageId;

    private String villageName;

    private LocalDateTime createTime;

    /**
     * 我能不能处理这条纠纷(后台抽屉据此决定按钮是否可用)。
     *
     * <p>判断依据是"管理员本人 > 数据范围 > 状态尚未终态"。
     * 让后端一次算好,和列表里的 `actions` 是同一个思路 ——
     * 前端各自 if 一遍,早晚和后端的状态机对不上。
     */
    private Boolean canHandle;

    /** 我能不能撤销(申请人本人 + 尚未终态) */
    private Boolean canCancel;
}
