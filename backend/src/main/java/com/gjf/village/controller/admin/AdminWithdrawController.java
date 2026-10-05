package com.gjf.village.controller.admin;

import com.gjf.village.annotation.Log;
import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.WithdrawAuditDTO;
import com.gjf.village.dto.WithdrawQueryDTO;
import com.gjf.village.service.WalletService;
import com.gjf.village.vo.WithdrawVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 后台提现审核。
 *
 * <p>数据级权限在 Service 层注入:村管只看本村。按钮级权限
 * ({@code withdraw:audit}) 在这里 —— <b>两层都要有</b>,
 * 前端隐藏按钮挡不住直接发 HTTP 请求。
 */
@RestController
@RequestMapping("/api/admin/withdraw")
@RequiredArgsConstructor
@Tag(name = "后台-提现审核", description = "本村提现申请的打款与驳回")
public class AdminWithdrawController {

    private final WalletService walletService;

    @GetMapping("/list")
    @RequiresPerm("withdraw:audit")
    @Operation(summary = "提现申请列表",
            description = "受数据级权限约束(村管只看本村),待审核的排在前面。"
                    + "收款账号已打码,只留后四位")
    public Result<PageResult<WithdrawVO>> list(WithdrawQueryDTO query) {
        return Result.success(walletService.pageAdminWithdraws(query));
    }

    @PutMapping("/{id}/audit")
    @RequiresPerm("withdraw:audit")
    @Log(module = "提现审核", operation = "提现打款 / 驳回")
    @Operation(summary = "审核提现",
            description = "pass=true 打款,pass=false 驳回并把钱退回余额(驳回时审核意见必填)。"
                    + "状态迁移用乐观锁,两个管理员同时点同一条时后者会被拒绝,"
                    + "不会退两次钱")
    public Result<Void> audit(@PathVariable Long id, @Valid @RequestBody WithdrawAuditDTO dto) {
        walletService.audit(id, dto);
        return Result.success(Boolean.TRUE.equals(dto.getPass()) ? "已确认打款" : "已驳回,金额退回用户余额", null);
    }
}
