package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gjf.village.dto.CreditQueryDTO;
import com.gjf.village.entity.UserCreditSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户信用概览 Mapper —— 数据源是视图 {@code v_user_credit_summary}。
 *
 * <p>不继承 {@code BaseMapper}:视图没有主键自增、不参与增删改,
 * 硬套一个 BaseMapper 会带来一堆用不上也调不通的方法。
 */
@Mapper
public interface UserCreditSummaryMapper {

    /** 单个用户的信用概览 */
    UserCreditSummary selectByUserId(@Param("userId") Long userId);

    /**
     * 信用列表(后台"信用管理"页)。
     *
     * <p>这是一个<b>带数据级权限的视图查询</b>:视图本身不带 WHERE,
     * 村组过滤由 Service 通过 {@code dataScopeHelper} 注入条件后在外层拼接。
     * 这与 {@code v_resource_list} 的处理方式相反 ——
     * 那个视图把"只看上架"固化进了定义,因为对所有调用方都一致;
     * 而"只看本村"因角色而异,只能留在外层。
     */
    IPage<UserCreditSummary> selectCreditPage(IPage<UserCreditSummary> page,
                                              @Param("q") CreditQueryDTO query);
}
