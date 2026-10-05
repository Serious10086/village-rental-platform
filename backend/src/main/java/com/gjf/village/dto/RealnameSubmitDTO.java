package com.gjf.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 实名认证提交。
 */
@Data
public class RealnameSubmitDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "请输入真实姓名")
    private String realName;

    @NotBlank(message = "请输入身份证号")
    @Pattern(regexp = "^[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx]$",
            message = "身份证号格式不正确")
    private String idCard;

    @NotBlank(message = "请上传身份证正面照")
    private String idCardFront;

    @NotBlank(message = "请上传身份证反面照")
    private String idCardBack;
}
