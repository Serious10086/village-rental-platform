package com.gjf.village.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 敏感词查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SensitiveWordQueryDTO extends BaseQueryDTO {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 关键词(匹配词本身) */
    private String keyword;

    private String category;

    private Integer status;
}
