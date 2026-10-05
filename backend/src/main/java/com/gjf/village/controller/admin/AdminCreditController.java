package com.gjf.village.controller.admin;

import com.gjf.village.annotation.Log;
import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.CreditAdjustDTO;
import com.gjf.village.dto.CreditQueryDTO;
import com.gjf.village.service.CreditService;
import com.gjf.village.vo.CreditRecordVO;
import com.gjf.village.vo.CreditSummaryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 后台 —— 信用管理。
 *
 * <p>对应需求里"村级管理员维护本村信用分规则"这一条。
 * 自动规则(订单完成 +2、好评 +1、差评 -2)覆盖不到的情形全在手动调整里:
 * 线下调解达成一致、经查实的恶意行为、给新用户的补偿性补分。
 */
@RestController
@RequestMapping("/api/admin/credit")
@RequiredArgsConstructor
@Tag(name = "后台-信用管理", description = "本村用户信用列表、信用流水、手动加减分")
public class AdminCreditController {

    private final CreditService creditService;

    @GetMapping("/list")
    @RequiresPerm("credit:list")
    @Operation(summary = "信用列表",
            description = "村级管理员只能看到本村用户。按信用分升序 —— "
                    + "这是管理页面不是排行榜,快跌破门槛的人排在最前面才有用")
    public Result<PageResult<CreditSummaryVO>> list(CreditQueryDTO query) {
        return Result.success(creditService.pageCreditList(query));
    }

    @GetMapping("/records")
    @RequiresPerm("credit:list")
    @Operation(summary = "指定用户的信用流水",
            description = "会校验该用户是否在当前管理员的数据范围内 —— "
                    + "列表接口的村组过滤挡不住「按 userId 直接查」这条路径")
    public Result<PageResult<CreditRecordVO>> records(CreditQueryDTO query) {
        return Result.success(creditService.pageUserRecords(query));
    }

    @PostMapping("/adjust")
    @RequiresPerm("credit:adjust")
    @Log(module = "信用管理", operation = "手动调整信用分")
    @Operation(summary = "手动调整信用分",
            description = "原因必填且会写进流水展示给用户 —— 用户问「为什么被扣分」时得答得上来")
    public Result<Void> adjust(@Valid @RequestBody CreditAdjustDTO dto) {
        creditService.adjustByAdmin(dto);
        return Result.success("信用分已调整", null);
    }
}
