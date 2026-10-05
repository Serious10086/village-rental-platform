package com.gjf.village.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 用户列表查询条件(后台)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQueryDTO extends BaseQueryDTO {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 关键词:匹配手机号或姓名 */
    private String keyword;

    /** 用户类型:1村民 2租户/买家 3管理员 */
    private Integer userType;

    /** 实名状态:0未提交 1待审核 2已认证 3已驳回 */
    private Integer realnameStatus;

    /** 账号状态:0禁用 1正常 2封禁 */
    private Integer accountStatus;
}
