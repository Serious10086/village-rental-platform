package com.gjf.village.service;

import com.gjf.village.entity.BizResourceCategory;

import java.util.List;

/**
 * 资源分类服务。
 */
public interface BizResourceCategoryService {

    /**
     * 分类树(两级)。
     *
     * <p>前台筛选器、发布页的分类选择都要用。属于公开数据,不需要登录。
     */
    List<BizResourceCategory> buildTree();

    /**
     * 平铺列表(只含叶子分类)。
     *
     * <p>资源实际上只挂在二级分类上,一级分类只是分组。
     * 需要"这个资源属于哪个品类"的地方用这个。
     */
    List<BizResourceCategory> listLeafCategories();

    /**
     * 取分类的默认风险等级。
     *
     * <p>发布资源时继承到资源上,"高风险资源需培训"由它驱动。
     */
    Integer getRiskLevel(Long categoryId);
}
