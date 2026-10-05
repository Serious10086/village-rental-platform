package com.gjf.village.controller.system;

import com.gjf.village.annotation.Log;
import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.VillageDTO;
import com.gjf.village.entity.SysVillage;
import com.gjf.village.service.SysVillageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统管理 —— 村组。
 *
 * <p>仅超级管理员可访问。村组不只是个下拉框选项,它是全系统数据级权限的锚点:
 * 用户的 village_id、资源的 village_id 都指向这里,村级管理员"只看得到本村数据"
 * 就是靠它划的界。所以维护入口收在超管手里。
 */
@RestController
@RequestMapping("/api/system/village")
@RequiredArgsConstructor
@Tag(name = "系统管理-村组", description = "村组树维护(仅超级管理员)")
public class SysVillageController {

    private final SysVillageService villageService;

    @GetMapping("/list")
    @RequiresPerm("village:list")
    @Operation(summary = "村组树",
            description = "两级结构(乡镇 → 村),含已停用的节点 —— 否则停用之后就再也启用不回来")
    public Result<List<SysVillage>> list() {
        return Result.success(villageService.listTree());
    }

    @PostMapping
    @RequiresPerm("village:edit")
    @Log(module = "村组管理", operation = "新增村组")
    @Operation(summary = "新增村组", description = "parentId 为空表示新增一个乡镇(顶级节点)")
    public Result<Long> add(@Valid @RequestBody VillageDTO dto) {
        return Result.success("添加成功", villageService.createVillage(dto));
    }

    @PutMapping
    @RequiresPerm("village:edit")
    @Log(module = "村组管理", operation = "修改村组")
    @Operation(summary = "修改村组",
            description = "上级不能是自己或自己的下级,否则树里会出现环")
    public Result<Void> update(@Valid @RequestBody VillageDTO dto) {
        villageService.updateVillage(dto);
        return Result.success("保存成功", null);
    }

    @DeleteMapping("/{id}")
    @RequiresPerm("village:edit")
    @Log(module = "村组管理", operation = "删除村组")
    @Operation(summary = "删除村组",
            description = "有下级村组、或还有用户/资源挂在该村组下时拒绝删除,避免数据失去归属")
    public Result<Void> delete(@PathVariable Long id) {
        villageService.deleteVillage(id);
        return Result.success("已删除", null);
    }
}
