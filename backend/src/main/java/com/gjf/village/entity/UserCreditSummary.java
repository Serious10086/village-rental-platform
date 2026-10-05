package com.gjf.village.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 用户信用概览,对应数据库视图 {@code v_user_credit_summary}。
 *
 * <p>字段与视图列一一对应,不参与 CRUD,只承接自定义查询的结果映射。
 *
 * <p><b>这个视图把"信用评分"从一句空话变成了三个可计算的口径</b> ——
 * 需求原文是"查看自身信用评分(基于交易履约、资源完好率、用户评价等)",
 * 三个词恰好对应这里的三列:
 * <table border="1">
 *   <tr><th>需求原词</th><th>字段</th><th>算法</th></tr>
 *   <tr><td>交易履约</td><td>{@code fulfillRate}</td>
 *       <td>已完成订单 / 订单总数</td></tr>
 *   <tr><td>资源完好率</td><td>{@code intactRate}</td>
 *       <td>资源相符度 ≥ 4 的评价占比</td></tr>
 *   <tr><td>用户评价</td><td>{@code avgScore}</td>
 *       <td>收到的评价均分</td></tr>
 * </table>
 *
 * <p>论文「信用评分模型」一节可以直接贴这个视图的定义 SQL 作为口径实现,
 * 比空谈公式有说服力。
 */
@Data
public class UserCreditSummary implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;
    private String realName;
    private String phone;
    private Long villageId;

    /** 当前信用分 */
    private Integer creditScore;

    private Integer finishOrderCount;
    private Integer cancelOrderCount;
    private Integer disputeOrderCount;
    private Integer totalOrderCount;

    /** 收到的评价均分(只算正常状态的评价) */
    private BigDecimal avgScore;

    private Integer evalCount;

    /** 资源完好率(%):资源相符度 ≥ 4 的占比 */
    private BigDecimal intactRate;

    /** 交易履约率(%):已完成订单 / 订单总数 */
    private BigDecimal fulfillRate;
}
