package com.gjf.village.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 分页响应封装。
 *
 * <p>把 MyBatis-Plus 的 {@link IPage} 转成前端友好的扁平结构,
 * 避免把框架内部的 orders / optimizeCountSql 等字段暴露给前端。
 *
 * @param <T> 列表元素类型
 */
@Data
public class PageResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 总记录数 */
    private Long total;

    /** 当前页数据 */
    private List<T> rows;

    /** 当前页码,从 1 开始 */
    private Long pageNum;

    /** 每页条数 */
    private Long pageSize;

    /** 总页数 */
    private Long pages;

    public PageResult() {
    }

    public PageResult(Long total, List<T> rows, Long pageNum, Long pageSize) {
        this.total = total;
        this.rows = rows;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
        this.pages = (pageSize == null || pageSize == 0) ? 0 : (total + pageSize - 1) / pageSize;
    }

    /**
     * 由 MyBatis-Plus 的分页对象构建。
     */
    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getTotal(), page.getRecords(), page.getCurrent(), page.getSize());
    }

    /**
     * 由 MyBatis-Plus 的分页对象构建,并把记录转换成 VO。
     *
     * @param page      分页对象(元素类型为实体)
     * @param voList    已转换好的 VO 列表
     */
    public static <E, T> PageResult<T> of(IPage<E> page, List<T> voList) {
        return new PageResult<>(page.getTotal(), voList, page.getCurrent(), page.getSize());
    }

    /**
     * 空分页结果。
     */
    public static <T> PageResult<T> empty(Long pageNum, Long pageSize) {
        return new PageResult<>(0L, List.of(), pageNum, pageSize);
    }
}
