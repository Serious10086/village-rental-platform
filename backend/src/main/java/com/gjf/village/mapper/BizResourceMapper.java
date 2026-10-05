package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gjf.village.dto.ResourceQueryDTO;
import com.gjf.village.entity.BizResource;
import com.gjf.village.entity.ResourceListView;
import com.gjf.village.vo.MyResourceVO;
import com.gjf.village.vo.ResourceDetailVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 资源 Mapper。
 *
 * <p>列表、详情、我的发布三个查询都写在 XML 里(见 resources/mapper/BizResourceMapper.xml),
 * 因为它们涉及视图、多表 JOIN、全文检索和动态排序 —— 这些用注解或
 * MyBatis-Plus 的条件构造器写出来可读性很差。
 */
@Mapper
public interface BizResourceMapper extends BaseMapper<BizResource> {

    /**
     * 前台资源搜索(数据源:v_resource_list 视图)。
     *
     * <p>视图里已经固化了 {@code resource_status = 1 AND deleted = 0},
     * 所以未审核、已下架、违规的资源不可能从这里漏出去。
     */
    IPage<ResourceListView> selectResourcePage(IPage<ResourceListView> page,
                                                @Param("q") ResourceQueryDTO query);

    /**
     * 我的发布(数据源:biz_resource 基表)。
     *
     * <p>必须用基表而不是视图 —— 发布者要看到自己全部状态的资源,
     * 包括待审核、已驳回、已下架的。
     */
    IPage<MyResourceVO> selectMyResourcePage(IPage<MyResourceVO> page,
                                             @Param("q") ResourceQueryDTO query);

    /**
     * 资源详情。
     *
     * <p>一次查询联出分类名、村组名、发布人信息,避免详情页发四五个请求。
     */
    ResourceDetailVO selectResourceDetail(@Param("id") Long id);

    /**
     * 按主键查资源并<b>加行锁</b>,供下单流程使用。
     *
     * <p>{@code FOR UPDATE} 是下单防超卖的关键:它把同一个资源上的并发下单
     * 请求串行化,后到的请求必须等前一个事务提交后才能读到资源行。
     * 只有拿到锁之后再去做时段冲突检测,检测结果才是可信的 ——
     * 否则两个请求会同时读到"没有冲突",然后双双插入订单。
     *
     * <p>必须手写而不是用 {@code selectById}:一是 {@code FOR UPDATE}
     * 在条件构造器里表达不出来;二是 {@code @TableLogic} 只在框架生成的
     * SQL 上生效,自定义 SQL 里得自己带上 {@code deleted = 0}。
     *
     * <p><b>调用方必须已经在事务中</b> —— 锁只在事务期间持有,
     * 事务一结束锁就释放,方法外再查就失去意义了。
     */
    BizResource selectByIdForUpdate(@Param("id") Long id);

    /**
     * 浏览量 +1,在数据库端自增。
     *
     * <p><b>为什么必须写成 SQL 自增,不能"查出来加一再写回"。</b>
     * 原来的写法是 {@code detail.getViewCount() + 1} 再 updateById ——
     * 两个人同时打开同一个资源详情页时,两边都读到 100、各自写回 101,
     * 结果是"看了两次、只加了一次"。这类丢失在早期无所谓(浏览量只是个装饰),
     * 但批次 8 的<b>村组热度推荐直接按 {@code view_count} 排序</b>,
     * 它一旦偏小,推荐位就会把真正热门的资源排到后面 —— 装饰品变成了输入数据,
     * 就得按数据的标准要求它。
     */
    int increaseViewCount(@Param("id") Long id);

    /**
     * 收藏数 +1 / -1,同样在数据库端自增(理由和浏览量一样)。
     *
     * <p>减一那条带 {@code favorite_count > 0} 的防御条件:正常情况下
     * 取消收藏必然对应一条已有的收藏记录,计数不会为 0;但冗余计数的正确性
     * 依赖"每次增删都成对",漏掉一次配对也不该让它掉成负数 ——
     * 负的收藏数在界面上没法解释。这和留言板的点赞计数是同一套写法。
     */
    int increaseFavoriteCount(@Param("id") Long id);

    int decreaseFavoriteCount(@Param("id") Long id);
}
