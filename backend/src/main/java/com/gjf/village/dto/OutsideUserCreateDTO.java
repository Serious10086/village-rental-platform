package com.gjf.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理员登记外来用户的请求。
 *
 * <p>与 {@link RegisterDTO} 的区别有两点:
 * <ul>
 *   <li>不收集密码 —— 统一用固定的初始密码,登记人线下告知本人</li>
 *   <li>不收集身份证号 —— 实名认证是另一条独立的审核流,
 *       在这里重复收集会和 {@code uk_id_card} 唯一索引以及实名状态打架。
 *       备案只解决"他归属哪个村",不解决"他是谁"</li>
 * </ul>
 *
 * <p>{@code villageId} 是<b>目标</b>村组,不是被登记人当前的归属 ——
 * 审核通过之前,这个人身上是没有村的。
 */
@Data
public class OutsideUserCreateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "请输入手机号")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "请输入姓名")
    @Size(max = 20, message = "姓名不能超过 20 个字")
    private String realName;

    /** 目标村组ID:备案通过后这个人的归属村 */
    @NotNull(message = "请选择归属村组")
    private Long villageId;

    /** 常住地址(选填) */
    @Size(max = 200, message = "地址不能超过 200 个字")
    private String address;
}
