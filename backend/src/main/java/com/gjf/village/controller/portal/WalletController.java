package com.gjf.village.controller.portal;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.PaymentQueryDTO;
import com.gjf.village.dto.WithdrawApplyDTO;
import com.gjf.village.dto.WithdrawQueryDTO;
import com.gjf.village.service.WalletService;
import com.gjf.village.vo.PaymentVO;
import com.gjf.village.vo.WalletVO;
import com.gjf.village.vo.WithdrawVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 钱包接口(前台)。
 *
 * <p>全部需要登录。三个查询接口在 Service 层强制把 {@code userId}
 * 覆盖成当前用户 —— 资金流水里有金额和订单号,能看到别人的
 * 就等于看到了别人在平台上做了多少生意。
 */
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
@Tag(name = "我的钱包", description = "余额概览、收益明细、提现申请与记录")
public class WalletController {

    private final WalletService walletService;

    @GetMapping("/info")
    @Operation(summary = "钱包概览",
            description = "余额、累计收益、累计提现、审核中金额。"
                    + "起提门槛也在这里下发,前端不要自己写死 10 —— "
                    + "写死后改门槛要同时改两边,漏改不报错,只是按钮的禁用条件错了")
    public Result<WalletVO> info() {
        return Result.success(walletService.getMyWallet());
    }

    @GetMapping("/earnings")
    @Operation(summary = "收益明细",
            description = "带 direction 字段:1 入账 / -1 出账。"
                    + "方向由服务端算好,前端不按 payType 自己判断正负号")
    public Result<PageResult<PaymentVO>> earnings(PaymentQueryDTO query) {
        return Result.success(walletService.pageMyEarnings(query));
    }

    @PostMapping("/withdraw")
    @Operation(summary = "申请提现",
            description = "扣款在申请时完成,驳回时退回。"
                    + "服务端先拿用户行的 FOR UPDATE 锁再做余额校验 —— "
                    + "顺序反过来的话,余额 100 时两笔并发的 100 元提现会双双通过")
    public Result<Long> withdraw(@Valid @RequestBody WithdrawApplyDTO dto) {
        return Result.success("提现申请已提交,等待管理员审核", walletService.applyWithdraw(dto));
    }

    @GetMapping("/withdraws")
    @Operation(summary = "我的提现记录")
    public Result<PageResult<WithdrawVO>> withdraws(WithdrawQueryDTO query) {
        return Result.success(walletService.pageMyWithdraws(query));
    }
}
