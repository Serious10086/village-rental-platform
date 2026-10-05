package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gjf.village.entity.BizResourceCategory;
import com.gjf.village.mapper.BizResourceCategoryMapper;
import com.gjf.village.service.BizResourceCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 资源分类服务实现。
 */
@Service
@RequiredArgsConstructor
public class BizResourceCategoryServiceImpl implements BizResourceCategoryService {

    private final BizResourceCategoryMapper categoryMapper;

    @Override
    public List<BizResourceCategory> buildTree() {
        List<BizResourceCategory> all = categoryMapper.selectList(
                new LambdaQueryWrapper<BizResourceCategory>()
                        .eq(BizResourceCategory::getStatus, 1)
                        .orderByAsc(BizResourceCategory::getSortOrder, BizResourceCategory::getId));
        return buildTreeRecursively(all, null);
    }

    @Override
    public List<BizResourceCategory> listLeafCategories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<BizResourceCategory>()
                        .eq(BizResourceCategory::getStatus, 1)
                        .isNotNull(BizResourceCategory::getParentId)
                        .orderByAsc(BizResourceCategory::getSortOrder, BizResourceCategory::getId));
    }

    @Override
    public Integer getRiskLevel(Long categoryId) {
        if (categoryId == null) {
            return 1;
        }
        BizResourceCategory category = categoryMapper.selectById(categoryId);
        if (category == null) {
            return 1;
        }
        // 叶子分类自带风险等级;万一取到的是一级分类(理论上不该发生),
        // 回退到它自己的 risk_level,再不行按最低风险处理
        return category.getRiskLevel() == null ? 1 : category.getRiskLevel();
    }

    private List<BizResourceCategory> buildTreeRecursively(List<BizResourceCategory> all, Long parentId) {
        return all.stream()
                .filter(c -> Objects.equals(c.getParentId(), parentId))
                .sorted(Comparator.comparing(BizResourceCategory::getSortOrder,
                        Comparator.nullsLast(Integer::compareTo)))
                .map(c -> {
                    c.setChildren(buildTreeRecursively(all, c.getId()));
                    return c;
                })
                .toList();
    }
}
