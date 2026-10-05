package com.gjf.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 敏感词新增 / 修改。
 */
@Data
public class SensitiveWordDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 修改时传入 */
    private Long id;

    @NotBlank(message = "请输入敏感词")
    @Size(max = 50, message = "敏感词不能超过 50 个字")
    private String word;

    /** 分类:政治/辱骂/广告/违法 */
    private String category;

    /** 处理方式:1替换为* 2直接拦截 */
    private Integer action;

    /** 状态:1启用 0停用 */
    private Integer status;
}
