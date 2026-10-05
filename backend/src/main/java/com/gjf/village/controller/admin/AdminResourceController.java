package com.gjf.village.controller.admin;

import com.gjf.village.annotation.Log;
import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.AuditDTO;
import com.gjf.village.dto.AuditQueryDTO;
import com.gjf.village.dto.ResourceQueryDTO;
import com.gjf.village.entity.SysAuditRecord;
import com.gjf.village.service.BizResourceService;
import com.gjf.village.service.SysAuditService;
import com.gjf.village.vo.AuditRecordVO;
import com.gjf.village.vo.MyResourceVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 后台 —— 资源管理与资源审核。
 *
 * <p>与前台资源接口的关键差别:<b>受数据级权限约束</b>。
 * 村级管理员只能看到本村的资源,审核也只能审本村的单子。
 */
@RestController
@RequestMapping("/api/admin/resource")
@RequiredArgsConstructor
@Tag(name = "后台-资源管理", description = "本村资源列表、资源发布审核")
public class AdminResourceController {

    private final BizResourceService resourceService;
    private final SysAuditService auditService;

    @GetMapping("/list")
    @RequiresPerm("resource:list")
    @Operation(summary = "资源列表",
            description = "返回全部状态的资源。村级管理员只能看到本村,前端传的 villageId 会被服务端覆盖")
    public Result<PageResult<MyResourceVO>> list(ResourceQueryDTO query) {
        return Result.success(resourceService.pageAdminResources(query));
    }

    // ── 资源发布审核 ──────────────────────────────────────────

    @GetMapping("/audit/list")
    @RequiresPerm("resource:audit")
    @Operation(summary = "资源审核列表",
            description = "只返回资源发布类型的待办。待审核的排在最前面")
    public Result<PageResult<AuditRecordVO>> auditList(AuditQueryDTO query) {
        // 强制限定业务类型,不让前端传 —— 同 AdminUserController 的理由
        query.setBizType(SysAuditRecord.BIZ_RESOURCE);
        return Result.success(auditService.pageAudits(query));
    }

    @PostMapping("/audit")
    @RequiresPerm("resource:audit")
    @Log(module = "资源审核", operation = "审核资源")
    @Operation(summary = "资源审核(通过/驳回)",
            description = "通过后资源上架可见;驳回时原因会写进资源记录,发布者在'我的发布'里能看到")
    public Result<Void> audit(@Valid @RequestBody AuditDTO dto) {
        dto.setBizType(SysAuditRecord.BIZ_RESOURCE);
        auditService.audit(dto);
        return Result.success("审核完成", null);
    }

    @GetMapping("/audit/pending-count")
    @RequiresPerm("resource:audit")
    @Operation(summary = "待审核数量", description = "供后台菜单角标使用")
    public Result<Long> pendingCount() {
        return Result.success(auditService.countPending(SysAuditRecord.BIZ_RESOURCE));
    }
}
