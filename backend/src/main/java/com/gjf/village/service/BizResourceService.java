package com.gjf.village.service;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.dto.ResourcePublishDTO;
import com.gjf.village.dto.ResourceQueryDTO;
import com.gjf.village.entity.ResourceListView;
import com.gjf.village.vo.MyResourceVO;
import com.gjf.village.vo.ResourceDetailVO;

import java.math.BigDecimal;

/**
 * 资源服务。
 */
public interface BizResourceService {

    /**
     * 前台资源搜索(只返回已上架的资源)。
     */
    PageResult<ResourceListView> pageResources(ResourceQueryDTO query);

    /**
     * 我的发布(返回全部状态的资源)。
     */
    PageResult<MyResourceVO> pageMyResources(ResourceQueryDTO query);

    /**
     * 后台资源管理(返回全部状态的资源,受数据级权限约束)。
     *
     * <p>与前台搜索的关键差别:
     * <ul>
     *   <li>能看到未上架、已下架、待审核的资源</li>
     *   <li>受数据范围限制 —— 村级管理员只能看到本村的资源</li>
     * </ul>
     * 前台搜索则刻意<b>不做</b>数据范围限制:买家本来就该能跨村找资源,
     * 那是这个平台存在的意义。两处的差别体现了数据级权限的边界 ——
     * 它不是"所有查询一律加村组条件",而是"管理类查询才加"。
     */
    PageResult<MyResourceVO> pageAdminResources(ResourceQueryDTO query);

    /**
     * 资源详情。
     *
     * <p>只返回已上架的资源;发布者本人可以查看自己任何状态的资源,
     * 村级管理员可以查看本村的任何状态资源。
     *
     * @param id 资源ID
     */
    ResourceDetailVO getDetail(Long id);

    /**
     * 发布资源。
     *
     * <p>会同时写入一条待审核记录,整体在一个事务里。
     *
     * @return 新资源的ID
     */
    Long publish(ResourcePublishDTO dto);

    /**
     * 编辑资源。
     *
     * <p><b>已上架的资源编辑后会被打回待审核状态</b> —— 否则发布者可以先提交
     * 一份合规内容通过审核,再把内容改成违规的,审核就形同虚设。
     */
    void update(ResourcePublishDTO dto);

    /**
     * 发布者主动下架。
     */
    void offline(Long id);

    /**
     * 重新上架(仅"临时下架"状态可以)。
     */
    void online(Long id);

    /**
     * 调整价格(不触发重新审核,因为价格变动不影响内容合规性)。
     */
    void adjustPrice(Long id, BigDecimal price);

    /**
     * 删除资源(逻辑删除)。
     */
    void delete(Long id);
}
