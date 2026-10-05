package com.gjf.village.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 资源搜索条件。
 *
 * <p>对应需求里的"按类别、位置(村/组)、价格区间、交易方式(租/售)、可用日期筛选"。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ResourceQueryDTO extends BaseQueryDTO {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 关键词。
     *
     * <p>走 MySQL 的 ngram 中文全文索引(MATCH ... AGAINST),
     * 而不是 LIKE '%关键词%' —— 后者前置通配符用不上任何索引,是全表扫描。
     */
    private String keyword;

    /**
     * 分类ID。
     *
     * <p>传一级分类时,会自动展开成"该分类 + 其下所有子分类"。
     * 因为用户点"农机具"时,期望看到拖拉机、收割机等所有子类。
     */
    private Long categoryId;

    /** 交易方式:1出租 2出售 */
    private Integer tradeType;

    /** 最低价格 */
    private BigDecimal minPrice;

    /** 最高价格 */
    private BigDecimal maxPrice;

    /**
     * 期望可用的起始日期。
     *
     * <p>与 {@link #availableEndDate} 一起表示"我这段日子想用"。
     * 筛选逻辑是资源可租窗口与查询窗口<b>有重叠</b>,而不是完全包含 ——
     * 用户问"下周能租吗",只要资源下周开放中就满足,不必它全年开放。
     */
    private LocalDate availableStartDate;

    /** 期望可用的结束日期 */
    private LocalDate availableEndDate;

    // ── 以下是"我的发布"/后台管理专用,前台搜索不会用到 ──────────

    /** 资源状态:0待审核 1已上架 2临时下架 3交易中 4已完成 5审核驳回 6违规下架 */
    private Integer resourceStatus;

    /** 发布人ID(由 DataScopeHelper 或"我的发布"注入) */
    private Long publisherId;

    /** 排序方式:newest 最新(默认) / priceAsc 价格升 / priceDesc 价格降 / popular 最热 */
    private String sortBy = "newest";
}
