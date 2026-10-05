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
 * 提现申请表。
 *
 * <p><b>全系统唯一会扣减余额的入口</b>,所以并发正确性在这里是硬要求。
 *
 * <p><b>扣款时机选在"申请"而不是"打款":</b>申请时就把钱从余额里划走,
 * 驳回时再退回。反过来(打款时才扣)会有一个致命窗口 ——
 * 用户余额 100 元,同时提交两笔 100 元的提现,两笔都能通过"余额够"的校验,
 * 都在等待管理员打款;管理员先打第一笔,余额变 0,再打第二笔时
 * 余额已经不够,但这笔提现已进入流程,要么赖账要么让余额变负。
 *
 * <p>先扣款则把校验和扣减压进同一个事务的同一把行锁里,窗口根本不存在。
 * 这也是余额类系统的通行做法:提现意向一旦提交,资金就处于冻结状态。
 */
@Data
@TableName("biz_withdraw")
public class BizWithdraw implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ── 收款方式 ────────────────────────────────────────────────
    public static final int ACCOUNT_WECHAT = 1;     // 微信
    public static final int ACCOUNT_ALIPAY = 2;     // 支付宝
    public static final int ACCOUNT_BANK = 3;       // 银行卡

    // ── 状态 ────────────────────────────────────────────────────
    public static final int STATUS_PENDING = 0;     // 待审核(钱已冻结)
    public static final int STATUS_PAID = 1;        // 已打款
    public static final int STATUS_REJECTED = 2;    // 已驳回(钱已退回)

    /** 单笔最低提现金额 */
    public static final BigDecimal MIN_AMOUNT = new BigDecimal("10.00");

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 提现单号,如 WD20260926000001 */
    private String withdrawNo;

    private Long userId;

    /** 提现金额 */
    private BigDecimal amount;

    /** 收款方式,见上方常量 */
    private Integer accountType;

    /** 收款账号 */
    private String accountNo;

    /** 收款人姓名 */
    private String accountName;

    /** 状态,见上方常量 */
    private Integer status;

    /** 审核人ID */
    private Long auditUserId;

    /** 审核意见 —— 驳回时必填,让用户知道为什么 */
    private String auditRemark;

    private LocalDateTime applyTime;

    private LocalDateTime auditTime;

    /** 打款时间 */
    private LocalDateTime finishTime;

    /** 村组ID,数据级权限锚点 */
    private Long villageId;

    // ── 文案 ────────────────────────────────────────────────────
    //
    // 放在实体里而不是各页面的 v-if 里:这两个映射既要在前台
    // "我的提现记录"用,也要在后台"提现审核"用,还要在站内信的
    // 通知文案里用。散开写三遍,加一种收款方式时必然漏一处。

    public static String accountTypeName(Integer type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case ACCOUNT_WECHAT -> "微信";
            case ACCOUNT_ALIPAY -> "支付宝";
            case ACCOUNT_BANK -> "银行卡";
            default -> "未知";
        };
    }

    public static String statusName(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case STATUS_PENDING -> "待审核";
            case STATUS_PAID -> "已打款";
            case STATUS_REJECTED -> "已驳回";
            default -> "未知";
        };
    }
}
