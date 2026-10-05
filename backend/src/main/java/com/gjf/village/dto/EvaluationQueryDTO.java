package com.gjf.village.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 评价列表查询条件。
 *
 * <p>继承 {@link BaseQueryDTO} 拿到分页参数与数据级权限字段。
 *
 * <p>{@code toUserId} / {@code fromUserId} 这两个"看谁的评价"的字段
 * 由 Service 按场景强制写入,不接受前端传入 —— 否则可以拿任意用户的 ID
 * 去拉他的评价列表(虽然没有直接泄露隐私,但匿名评价会被穿透:
 * 按 fromUserId 一筛,就知道那条匿名评价是谁写的)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EvaluationQueryDTO extends BaseQueryDTO {

    /** 按订单筛选 */
    private Long orderId;

    /** 按资源筛选 */
    private Long resourceId;

    /** 评价人 —— 匿名评价受保护,这个字段只在特定场景由 Service 注入 */
    private Long fromUserId;

    /** 被评价人 —— "我收到的评价" */
    private Long toUserId;

    /** 只看差评(评分 ≤ 2),后台巡查用 */
    private Boolean onlyBad;

    /**
     * 评价状态过滤。
     *
     * <p>由 Service 按场景设置,不接受前端传入:
     * 前台一律 {@code = 1}(只看正常评价),后台不设(违规被隐藏的也要看得到)。
     * 让前端传的话,一个隐藏了违规评价的人只要把 status 设成 2
     * 就能把自己被藏起来的差评重新翻出来。
     */
    private Integer status;
}
