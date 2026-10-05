package com.gjf.village.controller.portal;

import com.gjf.village.common.result.Result;
import com.gjf.village.entity.BizResourceCategory;
import com.gjf.village.service.BizResourceCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 资源分类接口(公开)。
 *
 * <p>无需登录 —— 未注册的访客也应该能浏览分类、看看村里有什么。
 * 分类信息不涉及任何用户数据,开放访问没有安全问题。
 */
@RestController
@RequestMapping("/api/category")
@RequiredArgsConstructor
@Tag(name = "资源分类", description = "分类树与叶子分类,供筛选器和发布页使用")
public class CategoryController {

    private final BizResourceCategoryService categoryService;

    @GetMapping("/tree")
    @Operation(summary = "分类树", description = "两级结构(大类 → 具体品类),供级联选择器使用")
    public Result<List<BizResourceCategory>> tree() {
        return Result.success(categoryService.buildTree());
    }

    @GetMapping("/list")
    @Operation(summary = "叶子分类列表", description = "扁平列表,资源实际挂在这一层上")
    public Result<List<BizResourceCategory>> list() {
        return Result.success(categoryService.listLeafCategories());
    }
}
