package com.gjf.village.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 分页查询基类。
 *
 * <p>所有列表查询的 DTO 都继承它,带来两个好处:
 * <ol>
 *   <li>分页参数统一,不用每个 DTO 重复写 pageNum / pageSize</li>
 *   <li><b>数据级权限的过滤字段统一</b> —— Service 层调一次
 *       {@code dataScopeHelper.apply(query)} 就能把数据范围注入,
 *       不用每个查询方法各写一遍</li>
 * </ol>
 *
 * <p>第 2 点是它存在的主要理由。数据级权限最容易出的问题就是
 * "某个新加的查询方法忘了加过滤条件",把字段收到基类里能大幅降低这个风险。
 */
@Data
public class BaseQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 页码,从 1 开始 */
    private Long pageNum = 1L;

    /** 每页条数,默认 10;上限由 MyBatis-Plus 分页插件兜底(500) */
    private Long pageSize = 10L;

    /**
     * 可见的村组ID列表 —— 由 {@code DataScopeHelper.apply()} 注入,
     * <b>绝不接受前端传入</b>。
     *
     * <p>为什么用 List 而不是单个 Long:因为数据范围有四种,
     * 其中"本镇"要覆盖镇下所有村,天然是集合。
     * 用集合一种类型就能覆盖全部四种情况,Service 里不用写分支。
     *
     * <p><b>安全要点:</b>如果让前端传这个字段,村级管理员只要把它改成别的村ID
     * 就能看到别村数据,数据级权限直接失效。所以 Service 层查询前会无条件覆盖。
     */
    private List<Long> villageIds;

    /**
     * 发布人过滤条件 —— 数据范围为"仅本人"时由 DataScopeHelper 注入。
     */
    private Long publisherId;

    /** 计算偏移量,供手写 SQL 分页用 */
    public Long getOffset() {
        long num = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        long size = (pageSize == null || pageSize < 1) ? 10 : pageSize;
        return (num - 1) * size;
    }
}
