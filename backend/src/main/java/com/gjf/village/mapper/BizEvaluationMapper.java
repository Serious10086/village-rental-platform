package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gjf.village.dto.EvaluationQueryDTO;
import com.gjf.village.entity.BizEvaluation;
import com.gjf.village.vo.EvaluationVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 评价 Mapper。
 *
 * <p>评价列表要联出订单号、资源名/封面、评价人姓名头像 —— 这些字段
 * 分布在三张表上。不联的话,列表里每一条都要再查三次,
 * 一页 10 条就是 30 次查询。
 */
@Mapper
public interface BizEvaluationMapper extends BaseMapper<BizEvaluation> {

    /**
     * 评价列表。
     *
     * <p>数据源刻意用<b>基表 + 显式 JOIN</b> 而不是视图:评价的可见性规则
     * 因场景而异(前台看正常的、后台看全部的、自己看自己写的),
     * 固化进视图反而要建好几个。
     */
    IPage<EvaluationVO> selectEvaluationPage(IPage<EvaluationVO> page,
                                             @Param("q") EvaluationQueryDTO query);

    /** 某笔订单里,指定的人是否已经评过 —— 用于回显"已评价"状态 */
    BizEvaluation selectByOrderAndFrom(@Param("orderId") Long orderId,
                                       @Param("fromUserId") Long fromUserId);
}
