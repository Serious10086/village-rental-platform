package com.gjf.village.mapper;

import com.gjf.village.vo.RecommendItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 推荐候选集查询。
 *
 * <p>单独一个 Mapper 而不是塞进 {@code BizResourceMapper}:这里的两条查询
 * 和资源域的其他查询有个本质区别 —— <b>它们只为推荐服务</b>,
 * 排序口径、排除规则("不推自己的""不推刚看过的")都是推荐特有的。
 * 混进资源 Mapper 会让"资源列表为什么按这个顺序排"变得要翻两处才看得清。
 */
@Mapper
public interface RecommendMapper {

    /**
     * 按分类取候选。两个排除规则由调用方分别控制。
     *
     * <p><b>为什么"不推自己的"和"不推看过的"要分成两个开关</b>:
     * 首页「猜你喜欢」两样都要排(推自己发布的东西没有意义,
     * 推刚看完的东西显得很傻);而详情页的「相似资源」只排前者 ——
     * 它的语义是"去看看同类的其他东西",把一个用户看过的同类资源
     * 藏起来反而奇怪,而且在他把某个分类看遍之后,
     * 那个推荐区会直接空掉。
     *
     * @param categoryIds   候选分类
     * @param excludeUserId 排除谁发布的;<b>同时</b>是"排除谁看过的"的那个人。
     *                      两者用的是同一个人,所以共用一个参数
     * @param excludeViewed 是否排除 {@code excludeUserId} 浏览过的资源
     * @param villageId     用户的村组,用于把本村的排在前面。可以为 null
     */
    List<RecommendItemVO> selectByCategories(@Param("categoryIds") List<Long> categoryIds,
                                             @Param("excludeUserId") Long excludeUserId,
                                             @Param("excludeViewed") boolean excludeViewed,
                                             @Param("villageId") Long villageId,
                                             @Param("limit") int limit);

    /**
     * 按热度取(村组热度 / 全局兜底)。
     *
     * <p>{@code villageId} 传了就只取本村(村组热度),传 null 就是全局(冷启动兜底)。
     * 合并成一个方法而不是写两条几乎一样的 SQL,是因为它们除了那一个条件
     * 完全相同 —— 两条 SQL 的代价是改排序口径时得记得改两处。
     *
     * <p>{@code excludeViewed} 和 {@link #selectByCategories} 共用同一段
     * 过滤条件(见 XML 里的 candidateFilters),所以这里也得有这个参数。
     * <b>两处必须同时声明,少一个 MyBatis 会在运行时报
     * "Parameter 'excludeViewed' not found"</b> —— 这个错误是启动之后
     * 第一次调用才出现的,编译期发现不了。
     */
    List<RecommendItemVO> selectHot(@Param("villageId") Long villageId,
                                    @Param("excludeUserId") Long excludeUserId,
                                    @Param("excludeViewed") boolean excludeViewed,
                                    @Param("limit") int limit);
}
