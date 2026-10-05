package com.gjf.village.controller.portal;

import com.gjf.village.common.result.Result;
import com.gjf.village.entity.SysVillage;
import com.gjf.village.service.SysVillageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 村组接口(公开)。
 *
 * <p>注册页的村组下拉框需要在<b>登录之前</b>就能拿到数据,
 * 所以这个接口必须在白名单里。
 *
 * <p>它返回的信息是村组名称和编码 —— 属于公开信息,
 * 不含任何用户数据,所以开放访问没有安全问题。
 */
@RestController
@RequestMapping("/api/village")
@RequiredArgsConstructor
@Tag(name = "村组", description = "村组列表与树形结构,供注册页和筛选器使用")
public class VillageController {

    private final SysVillageService villageService;

    @GetMapping("/list")
    @Operation(summary = "村组列表", description = "平铺列表,供下拉框使用")
    public Result<List<SysVillage>> list() {
        return Result.success(villageService.listEnabled());
    }

    @GetMapping("/tree")
    @Operation(summary = "村组树", description = "两级结构(乡镇 → 村),供级联选择器使用")
    public Result<List<SysVillage>> tree() {
        return Result.success(villageService.buildTree());
    }
}
