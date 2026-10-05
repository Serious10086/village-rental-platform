package com.gjf.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 修改密码请求。
 *
 * <p>刻意<b>不带 userId</b>:这个接口只允许本人改自己的密码,改谁的在 Service 里
 * 从登录态取。带上 userId 就相当于开了一个"改别人密码"的入口 ——
 * 只要有任何一个分支漏掉校验,就是直接接管他人账号。
 */
@Data
public class ChangePasswordDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 原密码。必须验,否则拿到 token 就能改密码,相当于 token 永不过期 */
    @NotBlank(message = "请输入原密码")
    private String oldPassword;

    /** 新密码。长度规则与注册保持一致 */
    @NotBlank(message = "请输入新密码")
    @Size(min = 6, max = 20, message = "密码长度需在 6-20 位之间")
    private String newPassword;
}
