package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 通用审核流水表。
 *
 * <p>实名认证、资源发布、外来用户备案、提现四条审核流共用一张表,
 * 靠 bizType 区分。四条流的字段完全同构,拆成四张表会有大量重复。
 *
 * <p>代价是 bizId 是<b>多态关联</b>,建不了物理外键 —— 这是刻意的取舍,
 * 答辩时值得主动说明。
 */
@Data
@TableName("sys_audit_record")
public class SysAuditRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务类型:实名认证 */
    public static final int BIZ_REALNAME = 1;
    /** 业务类型:资源发布 */
    public static final int BIZ_RESOURCE = 2;
    /** 业务类型:外来用户备案 */
    public static final int BIZ_OUTSIDE = 3;
    /** 业务类型:提现 */
    public static final int BIZ_WITHDRAW = 4;

    /** 审核状态:待审核 */
    public static final int STATUS_PENDING = 0;
    /** 审核状态:通过 */
    public static final int STATUS_APPROVED = 1;
    /** 审核状态:驳回 */
    public static final int STATUS_REJECTED = 2;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Integer bizType;
    private Long bizId;
    private Long applicantId;
    private Long villageId;
    private Long auditorId;
    private Integer auditStatus;
    private String auditRemark;

    /**
     * 提交时间。
     *
     * <p><b>这里刻意不标 {@code @TableField(fill = FieldFill.INSERT)}。</b>
     * 标了的话 MyBatis-Plus 会把该列放进 INSERT 语句,而我们通用的自动填充
     * 处理器只认 createTime / updateTime 两个字段名,结果就是显式插入 NULL,
     * 反而把数据库的 {@code DEFAULT CURRENT_TIMESTAMP} 顶掉了 ——
     * 报错是 {@code Column 'submit_time' cannot be null}。
     *
     * <p>不标的话,该字段为 null 时 MyBatis-Plus 会把它从 INSERT 中省略,
     * 数据库默认值正常生效。
     */
    private LocalDateTime submitTime;

    private LocalDateTime auditTime;
}
