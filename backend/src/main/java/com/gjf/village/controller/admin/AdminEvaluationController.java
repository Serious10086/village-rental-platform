package com.gjf.village.controller.admin;

import com.gjf.village.annotation.Log;
import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.EvaluationQueryDTO;
import com.gjf.village.service.BizEvaluationService;
import com.gjf.village.vo.EvaluationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 后台 —— 评价管理。
 *
 * <p>与前台最大的差别是<b>能看到被隐藏的评价</b>。这一点必须如此:
 * 如果隐藏之后就再也查不到,管理员误操作隐藏了一条评价就永远恢复不了。
 */
@RestController
@RequestMapping("/api/admin/evaluation")
@RequiredArgsConstructor
@Tag(name = "后台-评价管理", description = "本村评价巡查、隐藏/恢复违规评价")
public class AdminEvaluationController {

    private final BizEvaluationService evaluationService;

    @GetMapping("/list")
    @RequiresPerm("evaluation:list")
    @Operation(summary = "评价列表",
            description = "村级管理员只能看到本村评价;含被隐藏的。"
                    + "传 onlyBad=true 可只看差评,便于巡查")
    public Result<PageResult<EvaluationVO>> list(EvaluationQueryDTO query) {
        return Result.success(evaluationService.pageAdmin(query));
    }

    @PutMapping("/{id}/hide")
    @RequiresPerm("evaluation:hide")
    @Log(module = "评价管理", operation = "隐藏违规评价")
    @Operation(summary = "隐藏违规评价",
            description = "隐藏后不再计入「评价均分」和「资源完好率」两个信用口径 "
                    + "—— 这正是 v_user_credit_summary 视图里 status = 1 那行条件的意义")
    public Result<Void> hide(@PathVariable Long id) {
        evaluationService.hide(id);
        return Result.success("评价已隐藏", null);
    }

    @PutMapping("/{id}/show")
    @RequiresPerm("evaluation:hide")
    @Log(module = "评价管理", operation = "恢复被隐藏的评价")
    @Operation(summary = "恢复被隐藏的评价")
    public Result<Void> show(@PathVariable Long id) {
        evaluationService.show(id);
        return Result.success("评价已恢复", null);
    }
}
