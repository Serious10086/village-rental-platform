package com.gjf.village.controller.admin;

import com.gjf.village.annotation.Log;
import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.AuditDTO;
import com.gjf.village.dto.AuditQueryDTO;
import com.gjf.village.dto.OutsideUserCreateDTO;
import com.gjf.village.dto.UserQueryDTO;
import com.gjf.village.entity.SysAuditRecord;
import com.gjf.village.service.SysAuditService;
import com.gjf.village.service.SysUserService;
import com.gjf.village.vo.AuditRecordVO;
import com.gjf.village.vo.UserListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 后台 —— 用户管理、实名审核与外来用户备案。
 *
 * <p>这个控制器是三层权限的完整示范:菜单级(前端路由)、
 * 按钮级({@code @RequiresPerm})、数据级(Service 里的 DataScopeHelper)。
 */
@RestController
@RequestMapping("/api/admin/user")
@RequiredArgsConstructor
@Tag(name = "后台-用户管理", description = "用户列表、实名认证审核与外来用户备案")
public class AdminUserController {

    private final SysUserService userService;
    private final SysAuditService auditService;

    @GetMapping("/list")
    @RequiresPerm("user:list")
    @Operation(summary = "用户列表",
            description = "村级管理员只能看到本村用户;超管看全部。前端传的 villageId 会被服务端覆盖")
    public Result<PageResult<UserListVO>> list(UserQueryDTO query) {
        return Result.success(userService.pageUsers(query));
    }

    // ── 实名认证审核 ──────────────────────────────────────────

    @GetMapping("/audit/list")
    @RequiresPerm("user:realname:audit")
    @Operation(summary = "实名审核列表",
            description = "只返回实名认证类型的待办。ID 号在列表里是脱敏的,详情接口才返回完整值")
    public Result<PageResult<AuditRecordVO>> auditList(AuditQueryDTO query) {
        // 强制限定业务类型 —— 不让前端传,否则这个接口就能被用来查资源审核单,
        // 绕过资源审核的权限检查
        query.setBizType(SysAuditRecord.BIZ_REALNAME);
        return Result.success(auditService.pageAudits(query));
    }

    @PostMapping("/audit")
    @RequiresPerm("user:realname:audit")
    @Log(module = "实名审核", operation = "审核实名认证")
    @Operation(summary = "实名审核(通过/驳回)",
            description = "驳回时必须填写原因。审核通过后用户才能发布资源和下单")
    public Result<Void> audit(@Valid @RequestBody AuditDTO dto) {
        dto.setBizType(SysAuditRecord.BIZ_REALNAME);
        auditService.audit(dto);
        return Result.success("审核完成", null);
    }

    // ── 外来用户备案 ──────────────────────────────────────────
    //
    // 放在同一个控制器里而不是新建一个:菜单 302 挂在「用户管理」下、
    // 权限前缀是 user:,而且用户域的审核端点本来就都在这个类里

    @GetMapping("/outside/list")
    @RequiresPerm("user:outside")
    @Operation(summary = "外来用户备案列表",
            description = "只返回备案类型的单子。村级管理员只能看到登记在本村的备案")
    public Result<PageResult<AuditRecordVO>> outsideList(AuditQueryDTO query) {
        // 同样强制限定业务类型,理由见 auditList
        query.setBizType(SysAuditRecord.BIZ_OUTSIDE);
        return Result.success(auditService.pageAudits(query));
    }

    @PostMapping("/outside")
    @RequiresPerm("user:outside")
    @Log(module = "外来用户备案", operation = "登记外来用户")
    @Operation(summary = "登记外来用户",
            description = "代外来人员建账号并生成待审核的备案单。此时该用户还没有归属村组,"
                    + "要等备案通过才写入")
    public Result<Long> createOutside(@Valid @RequestBody OutsideUserCreateDTO dto) {
        return Result.success("已登记,待确认", auditService.submitOutside(dto));
    }

    @PostMapping("/outside/audit")
    @RequiresPerm("user:outside")
    @Log(module = "外来用户备案", operation = "审核外来用户备案")
    @Operation(summary = "审核外来用户备案(通过/驳回)",
            description = "通过后把备案的目标村组写入该用户的归属村组;驳回则保持无归属")
    public Result<Void> auditOutside(@Valid @RequestBody AuditDTO dto) {
        dto.setBizType(SysAuditRecord.BIZ_OUTSIDE);
        auditService.audit(dto);
        return Result.success("审核完成", null);
    }
}
