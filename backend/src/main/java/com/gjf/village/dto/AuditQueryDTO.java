package com.gjf.village.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 审核待办查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AuditQueryDTO extends BaseQueryDTO {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务类型:1实名认证 2资源发布 3外来用户备案;不传表示全部 */
    private Integer bizType;

    /** 审核状态:0待审核 1通过 2驳回;不传表示全部 */
    private Integer auditStatus;

    /** 关键词:匹配申请人姓名/手机号、资源名称 */
    private String keyword;
}
