package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gjf.village.dto.OrderQueryDTO;
import com.gjf.village.entity.BizOrder;
import com.gjf.village.entity.OrderDetailView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * 订单 Mapper。
 */
@Mapper
public interface BizOrderMapper extends BaseMapper<BizOrder> {

    /**
     * 订单列表(数据源:v_order_detail 视图)。
     *
     * <p>视图里已经联好了资源、买卖双方、村组,列表页不用再补查询。
     */
    IPage<OrderDetailView> selectOrderPage(IPage<OrderDetailView> page,
                                           @Param("q") OrderQueryDTO query);

    /** 订单详情 */
    OrderDetailView selectOrderDetail(@Param("id") Long id);

    /**
     * 统计与给定租期冲突的订单数 —— 时段冲突检测的核心查询。
     *
     * <p>判定用的是标准的<b>区间重叠</b>公式:
     * <pre>
     *   已有租期.开始 &lt;= 新租期.结束  AND  已有租期.结束 &gt;= 新租期.开始
     * </pre>
     * 只要两个区间有任意一天重合就算冲突。这里不能写成"完全包含" ——
     * 用户问"下周能租吗",只要资源下周有一部分空闲就该算可租,
     * 没必要要求资源整段时间都空着。
     *
     * <p>已取消(5)和卖家已拒绝(7)的订单不计入 —— 它们从未真正成立。
     * 这个口径写在 {@code OrderStatusEnum.occupiesSchedule()} 里,
     * 与视图/业务代码保持同一处定义。
     *
     * <p><b>本查询必须在持有资源行锁的事务内执行</b>,否则两个并发请求
     * 会同时读到 0 条冲突然后双双插入。见
     * {@code BizOrderServiceImpl#create}。
     */
    int countConflictOrders(@Param("resourceId") Long resourceId,
                            @Param("startDate") LocalDate startDate,
                            @Param("endDate") LocalDate endDate);
}
