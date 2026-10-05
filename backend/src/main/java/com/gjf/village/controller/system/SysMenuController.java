package com.gjf.village.controller.system;

import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.result.Result;
import com.gjf.village.entity.SysMenu;
import com.gjf.village.service.SysMenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统管理 —— 菜单。
 *
 * <p>仅超级管理员可访问。菜单树包含按钮级节点(menu_type = F),
 * 是"权限粒度细化到按钮"这条验收要求的直接证据 ——
 * 打开这个接口的返回,能看到每个按钮对应的权限标识。
 */
@RestController
@RequestMapping("/api/system/menu")
@RequiredArgsConstructor
@Tag(name = "系统管理-菜单", description = "完整菜单树,含按钮级权限节点(仅超级管理员)")
public class SysMenuController {

    private final SysMenuService menuService;

    @GetMapping("/list")
    @RequiresPerm("menu:list")
    @Operation(summary = "全部菜单(平铺)",
            description = "包含 M目录 / C菜单 / F按钮 三种节点,按钮节点的 perms 即权限标识")
    public Result<List<SysMenu>> list() {
        return Result.success(menuService.listAllMenus());
    }
}
