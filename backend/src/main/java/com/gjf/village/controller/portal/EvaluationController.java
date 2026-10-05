package com.gjf.village.controller.portal;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.EvaluationCreateDTO;
import com.gjf.village.dto.EvaluationQueryDTO;
import com.gjf.village.dto.EvaluationReplyDTO;
import com.gjf.village.service.BizEvaluationService;
import com.gjf.village.vo.EvaluationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 前台评价接口。
 *
 * <p>写在评价里的"我是谁、我评的是谁"全部由服务端从订单里推出来,
 * 接口只收订单ID和评分。<b>这不是省事,是安全</b>:
 * 一旦允许前端传 toUserId,任何人都能伪造评价记录去刷分,
 * 而数据库的唯一约束只在"同一人对同一单"这一维度生效,挡不住这种情况。
 */
@RestController
@RequestMapping("/api/evaluation")
@RequiredArgsConstructor
@Tag(name = "订单评价", description = "提交评价、回复评价、查看评价")
public class EvaluationController {

    private final BizEvaluationService evaluationService;

    @PostMapping("/create")
    @Operation(summary = "提交评价",
            description = "仅限已完成的订单,一单一评。评分会带动被评价人的信用分变动")
    public Result<Long> create(@Valid @RequestBody EvaluationCreateDTO dto) {
        return Result.success("评价提交成功", evaluationService.create(dto));
    }

    @PutMapping("/{id}/reply")
    @Operation(summary = "回复评价",
            description = "只有被评价人可以回复,且只能回复一次 —— 差评的申诉通道")
    public Result<Void> reply(@PathVariable Long id,
                              @Valid @RequestBody EvaluationReplyDTO dto) {
        evaluationService.reply(id, dto);
        return Result.success("回复成功", null);
    }

    @GetMapping("/received")
    @Operation(summary = "我收到的评价")
    public Result<PageResult<EvaluationVO>> received(EvaluationQueryDTO query) {
        return Result.success(evaluationService.pageReceived(query));
    }

    @GetMapping("/sent")
    @Operation(summary = "我发出的评价")
    public Result<PageResult<EvaluationVO>> sent(EvaluationQueryDTO query) {
        return Result.success(evaluationService.pageSent(query));
    }

    @GetMapping("/resource/{resourceId}")
    @Operation(summary = "某个资源下的评价(公开)",
            description = "只返回正常状态的评价,被管理员隐藏的不会露出来")
    public Result<PageResult<EvaluationVO>> byResource(@PathVariable Long resourceId,
                                                       EvaluationQueryDTO query) {
        query.setResourceId(resourceId);
        return Result.success(evaluationService.pageByResource(query));
    }

    @GetMapping("/order/{orderId}")
    @Operation(summary = "某笔订单的评价情况",
            description = "订单详情页据此显示「待评价」还是「已评价」")
    public Result<PageResult<EvaluationVO>> byOrder(@PathVariable Long orderId) {
        return Result.success(evaluationService.listByOrder(orderId));
    }
}
