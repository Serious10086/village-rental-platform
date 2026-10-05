package com.gjf.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 注册请求。
 *
 * <p>注意这里<b>不</b>包含身份证号 —— 实名认证是单独的流程(批次 4),
 * 注册时只填基本信息,之后在个人中心提交身份证由村级管理员审核。
 * 这样注册门槛低,也符合"村级管理员审核实名信息"的业务要求。
 */
@Data
public class RegisterDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "请输入手机号")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "请输入密码")
    @Size(min = 6, max = 20, message = "密码长度需在 6-20 位之间")
    private String password;

    @NotBlank(message = "请输入姓名")
    @Size(max = 20, message = "姓名不能超过 20 个字")
    private String realName;

    /**
     * 所属村组ID。
     *
     * <p>村民注册时必须选择村组 —— 它决定了后续由哪个村级管理员审核他的实名信息,
     * 也是他发布资源时的默认归属村。
     */
    private Long villageId;

    /**
     * 用户类型:1村民(资源发布者) 2租户/买家(资源使用者)。
     *
     * <p>注册时由用户选择身份,但不影响后续 —— 任何人都能既发布又租用,
     * 这个字段主要用于默认界面和统计。
     */
    private Integer userType = 2;

    /** 家庭住址(选填) */
    @Size(max = 200, message = "地址不能超过 200 个字")
    private String address;
}
