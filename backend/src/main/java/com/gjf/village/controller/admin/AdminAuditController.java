package com.gjf.village.controller.admin;

import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.result.Result;
import com.gjf.village.service.SysAuditService;
import com.gjf.village.vo.AuditRecordVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审核详情(实名与资源共用)。
 *
 * <p>两个注解值之间是「或」的关系 —— 拥有其中任一权限即可访问。
 * 具体这条审核单是实名还是资源,由 Service 按记录自身的 bizType 决定,
 * 并且同样会过一遍数据级权限(不能看别村的申请)。
 */
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
@Tag(name = "后台-审核详情", description = "审核单详情,含完整身份信息")
public class AdminAuditController {

    private final SysAuditService auditService;

    @GetMapping("/detail/{id}")
    @RequiresPerm({"user:realname:audit", "resource:audit", "user:outside"})
    @Operation(summary = "审核详情",
            description = "实名审核返回完整身份证号(列表接口是脱敏的)。需具备实名审核、资源审核或外来用户备案权限之一")
    public Result<AuditRecordVO> detail(@PathVariable Long id) {
        return Result.success(auditService.getAuditDetail(id));
    }
}
