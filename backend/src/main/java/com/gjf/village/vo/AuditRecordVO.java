package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 审核待办 / 审核记录列表项。
 *
 * <p>把审核流水和它对应的业务对象摊平在一个对象里返回。
 *
 * <p><b>为什么要摊平而不是让前端拿着 bizId 再查一次:</b>
 * 审核列表一屏 10 条,逐条回查业务详情就是 10 次额外请求 ——
 * 而且管理员一眼要看的是"谁提交了什么",本来就该在同一个响应里。
 */
@Data
public class AuditRecordVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ── 审核流水本身 ────────────────────────────────────────
    private Long id;
    private Integer bizType;
    private Long bizId;
    private Long applicantId;
    private Long villageId;
    private Integer auditStatus;
    private String auditRemark;
    private LocalDateTime submitTime;
    private LocalDateTime auditTime;

    // ── 申请人信息(LEFT JOIN sys_user) ─────────────────────
    private String applicantName;
    private String applicantPhone;
    private String applicantVillageName;
    private Integer applicantCredit;

    // ── 实名认证专有 ────────────────────────────────────────
    /**
     * 身份证号。
     *
     * <p>列表接口返回的是<b>脱敏后</b>的(前 6 位 + 后 4 位,中间打码)。
     * 管理员核对身份需要看完整的,点进详情页调 detail 接口才能拿到。
     * 列表页没有这个必要,而脱敏能避免"截图发群里"这类意外泄露。
     */
    private String idCard;

    private String idCardFront;
    private String idCardBack;
    private Integer realnameStatus;

    // ── 资源专有 ────────────────────────────────────────────
    private String resourceName;
    private String resourceCover;
    private String resourceDescription;
    private BigDecimal price;
    private Integer priceUnit;
    private Integer tradeType;
    private Integer riskLevel;
    private String categoryName;
    private Integer resourceStatus;

    // ── 审核人 ──────────────────────────────────────────────
    private String auditorName;
}
