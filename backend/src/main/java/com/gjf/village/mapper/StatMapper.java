package com.gjf.village.mapper;

import com.gjf.village.vo.OrderTrendVO;
import com.gjf.village.vo.StatItemVO;
import com.gjf.village.vo.VillageStatVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 统计查询。
 *
 * <p>四个查询,全部接受同一个 {@code villageIds} 参数(数据级权限注入的村组白名单;
 * null 表示不加限制,也就是超管的"全部")。**统一用白名单而不是"传一个 villageId"**:
 * 后者让每个调用方都得自己保证"我只传了自己有权的那个村",漏一处就是越权。
 *
 * <p>村级汇总走视图 {@code v_village_resource_stat} —— 那是批次 1 就设计好的
 * 读模型,村级统计、各村对比、后台公示三个页面共用一套口径。
 * 其余三个是图表数据,视图里没有,单独查。
 */
@Mapper
public interface StatMapper {

    /**
     * 各村的一行统计(来自视图)。
     *
     * @param villageIds 村组白名单;null 表示全部
     */
    List<VillageStatVO> selectVillageStats(@Param("villageIds") List<Long> villageIds);

    /**
     * 在架资源的分类分布(饼图)。
     *
     * <p>只统计**在架**的:待审核、已下架的东西出现在"资源分布"里,
     * 会让这张图回答不了它本来要回答的问题("村里现在有什么能租")。
     */
    List<StatItemVO> selectCategoryDist(@Param("villageIds") List<Long> villageIds);

    /**
     * 订单状态分布。
     *
     * <p>返回的 {@code name} 是**状态码字符串**(0-7),中文名在 Service 里
     * 用 {@code OrderStatusEnum} 翻译 —— 状态机的唯一定义处在那个枚举里,
     * 在 SQL 里再写一遍 CASE WHEN 就等于复制了一份。
     */
    List<StatItemVO> selectOrderStatusDist(@Param("villageIds") List<Long> villageIds);

    /**
     * 近 N 天的订单趋势(折线)。
     *
     * <p><b>只返回有订单的那些天</b>,不补零。补零要生成一张日期序列再左连接,
     * 那是报表系统的做法;而折线图缺的那几天,前端用日期轴渲染出来照样能看出
     * "那天一单没有" —— 起点和终点之间没有点,就是没有。
     */
    List<OrderTrendVO> selectOrderTrend(@Param("villageIds") List<Long> villageIds,
                                        @Param("days") int days);
}
