package com.gjf.village.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 后台账号查询条件。
 *
 * <p>账号管理只面对管理员(user_type = 3),所以这里没有 userType 字段 ——
 * 它是写死在查询条件里的,不是给前端选的。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AccountQueryDTO extends BaseQueryDTO {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 关键词:匹配手机号或姓名 */
    private String keyword;

    /** 按角色筛选 */
    private Long roleId;

    /** 账号状态:0禁用 1正常 2封禁 */
    private Integer accountStatus;
}
