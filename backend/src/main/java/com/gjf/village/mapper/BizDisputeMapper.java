package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gjf.village.dto.DisputeQueryDTO;
import com.gjf.village.entity.BizDispute;
import com.gjf.village.vo.DisputeVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 纠纷 Mapper。
 *
 * <p>列表和详情共用同一段列定义(见 XML 里的 {@code disputeColumns}) ——
 * 后台的处理抽屉是"列表点开就展开",列表里有的字段详情里必须也有,
 * 否则点开的瞬间会有一片空白。两处共用一段 SQL 才能真正保证这一点,
 * 各自写一份迟早会漂移。
 */
@Mapper
public interface BizDisputeMapper extends BaseMapper<BizDispute> {

    /** 纠纷分页(列表页带着订单号和双方姓名,不用点进去才知道告的是谁) */
    IPage<DisputeVO> selectDisputePage(IPage<DisputeVO> page,
                                       @Param("q") DisputeQueryDTO query);

    /**
     * 纠纷详情。
     *
     * <p>比列表多出的只有订单摘要部分,但正是这部分让管理员能在
     * 同一个请求里看到"这笔订单原本是什么状态、金额多大" ——
     * 判断该不该退钱全靠它。
     */
    DisputeVO selectDisputeDetail(@Param("id") Long id);
}
