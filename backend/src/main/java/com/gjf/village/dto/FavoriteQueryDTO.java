package com.gjf.village.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 「我的收藏」查询条件。
 *
 * <p>只有一个关键词 —— 收藏列表天然按"我的"过滤,村组、发布人这些维度
 * 在这里没有意义。做成独立的 DTO 而不是复用 {@code ResourceQueryDTO}:
 * 后者有十几个筛选字段(分类、价格区间、交易方式、排序…),
 * 收藏列表一个都不用,复用会让读代码的人以为那些筛选在收藏页也生效。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FavoriteQueryDTO extends BaseQueryDTO {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 按资源名称模糊搜 */
    private String keyword;
}
