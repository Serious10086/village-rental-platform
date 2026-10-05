package com.gjf.village.service;

import com.gjf.village.vo.StatOverviewVO;

/**
 * 数据统计服务。
 *
 * <p>三个页面共用一套口径,差别只在范围:
 * <ul>
 *   <li>{@link #dashboard()} —— 工作台。村管看本村,超管看全平台</li>
 *   <li>{@link #village(Long)} —— 村级统计。村管强制本村;超管可指定村</li>
 *   <li>{@link #global()} —— 全局统计。超管专属的各村横向对比</li>
 * </ul>
 *
 * <p><b>为什么把"范围"的决定权放在服务端。</b>三个页面的前端长得不一样,
 * 但它们对"我能看哪些村"这个问题的答案是同一个 —— 由登录态决定。
 * 让前端传 villageId 来决定范围,就等于让前端决定自己能看什么。
 */
public interface StatService {

    /** 工作台:关键数字 + 分类分布 + 订单状态 + 近 30 天趋势 */
    StatOverviewVO dashboard();

    /**
     * 村级统计。
     *
     * @param villageId 只有超管能指定;<b>村管传什么都会被忽略</b>,一律看本村
     */
    StatOverviewVO village(Long villageId);

    /** 全局统计:全平台数字 + 各村明细 + 分类分布。仅超管 */
    StatOverviewVO global();
}
