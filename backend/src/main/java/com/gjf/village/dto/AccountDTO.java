package com.gjf.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 后台账号新增 / 修改。
 *
 * <p>{@code password} 只在新增时必填 —— 修改资料不带密码,
 * 改密码走单独的重置接口。两件事混在一个接口里,
 * "只想改个姓名"容易顺手把密码覆盖成空。
 */
@Data
public class AccountDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 修改时传入 */
    private Long id;

    /** 手机号,同时是登录账号 */
    @NotBlank(message = "请输入手机号")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 仅新增时必填;修改资料时忽略 */
    @Size(min = 6, max = 32, message = "密码长度需在 6-32 位之间")
    private String password;

    @NotBlank(message = "请输入姓名")
    @Size(max = 20, message = "姓名不能超过 20 个字符")
    private String realName;

    /** 所属村组。超级管理员可不绑定村组 */
    private Long villageId;

    /** 账号状态:0禁用 1正常 2封禁 */
    private Integer accountStatus;

    private List<Long> roleIds;
}
