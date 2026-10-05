package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 纠纷协调表。
 *
 * <p><b>纠纷为什么必须是独立的一张表,而不是订单上的一个状态位:</b>
 * 订单状态只能表达"这单现在卡在争议里",而纠纷要记的东西远不止这个 ——
 * 谁提出的、依据是什么、诉求多少钱、谁在什么时候调解的、结论是什么。
 * 这些字段塞进订单表,订单表就同时背着"交易"和"争议"两套语义,
 * 而且一笔订单理论上可能反复产生争议(这次调解完,又出了新问题),
 * 一个状态位根本表达不了多个回合。
 *
 * <p><b>状态与订单状态是两条独立的线:</b>纠纷的 0/1/2/3 说的是"这件事
 * 处理到哪一步了",订单的 6 只是说"这单被争议挂起了"。两者同步变化,
 * 但含义不同 —— 混成一个字段一定会出现表达不了的组合。
 */
@Data
@TableName("biz_dispute")
public class BizDispute implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ── 纠纷类型 ────────────────────────────────────────────────
    public static final int TYPE_DAMAGE = 1;        // 损坏赔偿
    public static final int TYPE_DEPOSIT = 2;       // 押金/退款争议
    public static final int TYPE_NOT_MATCH = 3;     // 实物不符
    public static final int TYPE_OVERDUE = 4;       // 逾期未还
    public static final int TYPE_OTHER = 5;         // 其他

    // ── 纠纷状态 ────────────────────────────────────────────────
    public static final int STATUS_PENDING = 0;     // 待受理
    public static final int STATUS_HANDLING = 1;    // 处理中
    public static final int STATUS_MEDIATED = 2;    // 已调解
    public static final int STATUS_CLOSED = 3;      // 已关闭

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 纠纷编号,如 DP20260926000001 */
    private String disputeNo;

    /** 关联订单ID —— 后台处理纠纷时要能"调取订单数据作为依据" */
    private Long orderId;

    /** 申请人ID(买家或卖家任一方) */
    private Long applicantId;

    /** 被申请人ID(订单里的另一方) */
    private Long respondentId;

    /** 纠纷类型,见上方常量 */
    private Integer disputeType;

    /** 纠纷描述 */
    private String description;

    /** 证据图片,逗号分隔 */
    private String evidenceImages;

    /** 诉求金额,可为空(比如诉求只是"要求对方道歉") */
    private BigDecimal applyAmount;

    /**
     * 申请时的订单状态(2 待交付 / 3 使用中)。
     *
     * <p>订单被置为"纠纷中"后,原本走到哪一步的信息就丢了。
     * 调解结束后要恢复履约,必须知道恢复到哪个状态 ——
     * 所以这一列是在申请的那一刻把它<b>存下来</b>,而不是事后去猜。
     */
    private Integer orderStatusBefore;

    /** 纠纷状态,见上方常量 */
    private Integer status;

    /** 处理人ID(村级管理员或超管) */
    private Long handlerId;

    /** 调解结果 */
    private String handleResult;

    private LocalDateTime handleTime;

    /** 村组ID,数据级权限锚点 */
    private Long villageId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ── 文案 ────────────────────────────────────────────────────

    public static String typeName(Integer type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case TYPE_DAMAGE -> "损坏赔偿";
            case TYPE_DEPOSIT -> "押金/退款争议";
            case TYPE_NOT_MATCH -> "实物不符";
            case TYPE_OVERDUE -> "逾期未还";
            case TYPE_OTHER -> "其他";
            default -> "未知";
        };
    }

    public static String statusName(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case STATUS_PENDING -> "待受理";
            case STATUS_HANDLING -> "处理中";
            case STATUS_MEDIATED -> "已调解";
            case STATUS_CLOSED -> "已关闭";
            default -> "未知";
        };
    }
}
