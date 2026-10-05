package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gjf.village.entity.AiUserBehavior;
import com.gjf.village.vo.BehaviorCountVO;
import com.gjf.village.vo.CategoryPreferenceVO;
import com.gjf.village.vo.RecommendDailyVO;
import com.gjf.village.vo.RecommendStatVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户行为 Mapper —— 推荐算法的数据源。
 *
 * <p>写入走 {@code BaseMapper} 那套;手写 SQL 的是两条推荐主查询:
 * "这个用户偏好哪些分类"和"这些分类下有哪些他还没看过的好东西"。
 */
@Mapper
public interface AiUserBehaviorMapper extends BaseMapper<AiUserBehavior> {

    /**
     * 用户最近的分类偏好,按加权得分倒序。
     *
     * <p><b>为什么不是简单地 COUNT(*)</b>:一次下单和一次误点浏览,
     * 对"我喜欢什么"的表达强度差着量级。所以按行为类型给权重
     * (下单 5 / 评价 4 / 收藏 3 / 搜索 2 / 浏览 1),浏览还额外看停留时长 ——
     * 停 30 秒以上的浏览再多算 1 分,划过去就走的算 1 分。
     * 权重表写在 XML 里,每个数字旁边都有它为什么是那个数。
     *
     * @param userId 用户
     * @param days   只看最近多少天(太长会把"上个月感兴趣、现在早不看了"算进来)
     * @param limit  取前几个分类
     */
    List<CategoryPreferenceVO> selectTopCategories(@Param("userId") Long userId,
                                                  @Param("days") int days,
                                                  @Param("limit") int limit);

    /**
     * 推荐位点击率总览。
     *
     * <p>曝光是"推荐位被展示了几次"(behavior_type = 6),
     * 点击是"从推荐位点进详情页几次"(浏览且 source = 4)。
     * 两个都按 source = 4 过滤 —— 那个字段就是为这件事存在的。
     *
     * <p>点击率在 SQL 里算而不是取回来在 Java 里除:这样页面上显示的
     * 百分比一定和旁边那两个计数自洽。在 Java 里除的话,一旦有人改了
     * 取数的范围而忘了改计算,就会出现"3 除以 10 显示成 40%"这种
     * 没人能一眼看出来的错。
     *
     * @param villageIds 村组白名单,来自数据级权限;null 表示不加限制(超管)
     */
    RecommendStatVO selectRecommendStat(@Param("villageIds") List<Long> villageIds,
                                        @Param("days") int days);

    /**
     * 按天的点击率明细。
     *
     * <p><b>只会返回"有行为的那些天"</b> —— 一整天没人访问的日子不会出现
     * 在结果里,而不是补一行 0。补零需要生成一张日期序列再左连接,
     * 那是报表系统的做法;这里的用途是看趋势,断掉的那天不显示不影响判断。
     */
    List<RecommendDailyVO> selectRecommendDaily(@Param("villageIds") List<Long> villageIds,
                                               @Param("days") int days);

    /**
     * 各类行为的采集量,按类型升序。
     *
     * <p><b>为什么要有它:它是"推荐的数据管道还活着吗"的唯一可见指标。</b>
     * 推荐变笨通常没有报错 —— 某个行为忘了记、埋点断了、字段超长被吞掉,
     * 表现都只是"推荐不那么准了"。而采集量掉到 0 是看得见的。
     *
     * <p>只返回**有记录的那些类型**:一条都没有的类型不会补一行 0
     * (补零要在 SQL 里造一张类型表再左连接,而"缺哪一行"本身就是信息 ——
     * 前端按完整的类型清单去对照,缺的那个就是断掉的那条链路)。
     */
    List<BehaviorCountVO> selectBehaviorCounts(@Param("villageIds") List<Long> villageIds,
                                               @Param("days") int days);
}
