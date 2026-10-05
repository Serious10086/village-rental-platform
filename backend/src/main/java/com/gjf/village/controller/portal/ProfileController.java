package com.gjf.village.controller.portal;

import com.gjf.village.common.result.Result;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.dto.RealnameSubmitDTO;
import com.gjf.village.service.SysAuditService;
import com.gjf.village.service.SysUserService;
import com.gjf.village.vo.UserInfoVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 个人中心接口。
 *
 * <p>批次 4 先提供实名认证相关能力,后续批次会补上资料修改、信用详情、钱包等。
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Tag(name = "个人中心", description = "实名认证提交与状态查询")
public class ProfileController {

    private final SysAuditService auditService;
    private final SysUserService userService;

    @GetMapping("/profile")
    @Operation(summary = "获取个人信息", description = "含实名状态、信用分、余额等")
    public Result<UserInfoVO> profile() {
        return Result.success(userService.getUserInfo(UserContext.getUserId()));
    }

    @PostMapping("/realname")
    @Operation(summary = "提交实名认证",
            description = "提交后状态变为待审核,由所属村组的村级管理员审核")
    public Result<Void> submitRealname(@Valid @RequestBody RealnameSubmitDTO dto) {
        auditService.submitRealname(dto);
        return Result.success("已提交,请等待村级管理员审核", null);
    }
}
