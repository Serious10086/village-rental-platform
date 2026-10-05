package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 信用分变更流水表。
 *
 * <p><b>为什么不只在 sys_user 上存一个 credit_score 数字:</b>
 * 用户问"我为什么被扣了 10 分"时,只有一个总分是答不上来的。
 * 村管要"维护本村信用分规则"也没有审计依据。
 *
 * <p>{@code beforeScore} / {@code afterScore} 双记是关键:
 * 单看 {@code changeScore} 无法判断这笔变更是"从 100 加到 102"
 * 还是"从 52 加到 54"。有前后值才能对账,也才能在数据库层面
 * 校验出"流水串了"这类问题。
 *
 * <p>本批次(交易闭环)只写入"订单履约 +2"这一种变更,
 * 评价加分、违规扣分、管理员手动调整在后续批次补上。
 */
@Data
@TableName("biz_credit_record")
public class BizCreditRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务类型:订单履约 */
    public static final int BIZ_ORDER = 1;
    /** 业务类型:资源完好 */
    public static final int BIZ_RESOURCE_INTACT = 2;
    /** 业务类型:用户评价 */
    public static final int BIZ_EVALUATION = 3;
    /** 业务类型:管理员手动调整 */
    public static final int BIZ_MANUAL = 4;
    /** 业务类型:违规处罚 */
    public static final int BIZ_VIOLATION = 5;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 变更值,正加负减 */
    private Integer changeScore;

    private Integer beforeScore;

    private Integer afterScore;

    /** 变更原因,直接展示给用户看的文案 */
    private String reason;

    private Integer bizType;

    /** 关联业务ID(逻辑关联,无物理外键) */
    private Long bizId;

    /** 操作人ID,管理员手动调整时写入 */
    private Long operatorId;

    /** 村组ID —— 数据级权限锚点 */
    private Long villageId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
