package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 统计总览 —— 工作台 / 村级统计 / 全局统计三个页面共用这一个形状。
 *
 * <h3>为什么三个页面用一个 VO</h3>
 *
 * <p>它们要的东西是同一套:**几个关键数字 + 几张图**。差别只在<b>范围</b>
 * (本村 / 选定村 / 全平台)和<b>要不要各村明细</b>。为这三种范围各写一个
 * 只差几个字段的 VO,结果是改一处口径要改三个类,而它们迟早会漂开 ——
 * 到时候"工作台的订单数和统计页的对不上"这种问题很难查。
 *
 * <p>{@code villages} 只有全局统计会填,其余两个页面为 null。
 * 这是这个 VO 里唯一的"可选字段",所以单独说明了。
 *
 * <h3>{@code usageRate} 是重新算的,不是各村比率的平均</h3>
 *
 * <p>全平台的租售利用率 = (交易中 + 已完成) ÷ 资源总数,**用汇总后的两个数
 * 相除**,而不是把各村的利用率求平均。后者是统计上常见的错:一个只有 1 个资源
 * 的村利用率 100%,会把平均值显著拉高,而它代表不了整个平台。
 * (同一个道理:平均耗时的正确算法是"总耗时 ÷ 总数",不是"各次耗时的平均"。)
 */
@Data
public class StatOverviewVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ── 关键数字(卡片) ─────────────────────────────────────────
    /** 资源总数(含未上架的) */
    private long resourceTotal;
    /** 在架资源 */
    private long onlineCount;
    /** 可租 */
    private long rentCount;
    /** 在售 */
    private long sellCount;
    /** 高风险资源(需要持证/培训那类) */
    private long highRiskCount;
    /** 用户总数 */
    private long userTotal;
    /** 订单总数 */
    private long orderTotal;
    /** 已完成订单 */
    private long orderFinished;
    /** 使用中的订单 */
    private long orderUsing;
    /** 覆盖率:有订单的资源占比(%) —— 直接对应需求原文的"租售利用率" */
    private BigDecimal usageRate;

    // ── 图表 ────────────────────────────────────────────────────
    /** 在架资源的分类分布(饼图) */
    private List<StatItemVO> categoryDist;
    /** 订单状态分布(柱状/环形) */
    private List<StatItemVO> orderStatusDist;
    /** 近 30 天订单趋势(折线)。只有工作台要,其余为 null */
    private List<OrderTrendVO> orderTrend;

    // ── 各村明细(只有全局统计填) ──────────────────────────────
    /**
     * 各村的一行统计,来自视图 {@code v_village_resource_stat}。
     *
     * <p>只有"全局统计"会填它 —— 那个页面要的正是各村横向对比。
     * 另外两个页面看的是"一个范围的总数",不需要拆到村。
     */
    private List<VillageStatVO> villages;
}
