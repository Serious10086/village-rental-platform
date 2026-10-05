package com.gjf.village.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 提现单(前台"我的提现记录"与后台"提现审核"共用)。
 *
 * <p>同一套 VO 两处用,是因为两处要展示的字段完全重合:
 * 申请人要能看到审核意见,审核人也要能看到收款账号。
 * 差异只在<b>可见范围</b>上 —— 前台强制 {@code user_id = 我},
 * 后台按数据级权限过滤,而这个差异是查询条件的事,不是 VO 的事。
 */
@Data
public class WithdrawVO {

    private Long id;

    private String withdrawNo;

    private Long userId;

    private String userName;

    private String userPhone;

    private BigDecimal amount;

    /** 1微信 2支付宝 3银行卡 */
    private Integer accountType;

    private String accountTypeName;

    private String accountNo;

    private String accountName;

    /** 0待审核 1已打款 2已驳回 */
    private Integer status;

    private String statusName;

    private Long auditUserId;

    private String auditUserName;

    private String auditRemark;

    private LocalDateTime applyTime;

    private LocalDateTime auditTime;

    private LocalDateTime finishTime;

    private Long villageId;

    private String villageName;
}
