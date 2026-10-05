package com.gjf.village.controller.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.result.Result;
import com.gjf.village.entity.SysRole;
import com.gjf.village.mapper.SysRoleMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统管理 —— 角色。
 *
 * <p>仅超级管理员可访问。这里刻意作为"按钮级权限"的反面验证点:
 * 用村级管理员(战旗村支书)的 token 调这个接口,应当返回 403。
 */
@RestController
@RequestMapping("/api/system/role")
@RequiredArgsConstructor
@Tag(name = "系统管理-角色", description = "角色列表(仅超级管理员)")
public class SysRoleController {

    private final SysRoleMapper roleMapper;

    @GetMapping("/list")
    @RequiresPerm("role:list")
    @Operation(summary = "角色列表", description = "需要 role:list 权限,村级管理员调用会返回 403")
    public Result<List<SysRole>> list() {
        return Result.success(roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .orderByAsc(SysRole::getSortOrder)));
    }
}
