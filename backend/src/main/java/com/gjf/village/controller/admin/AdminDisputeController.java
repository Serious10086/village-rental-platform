package com.gjf.village.controller.admin;

import com.gjf.village.annotation.Log;
import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.DisputeHandleDTO;
import com.gjf.village.dto.DisputeQueryDTO;
import com.gjf.village.service.BizDisputeService;
import com.gjf.village.vo.DisputeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 后台纠纷处理。
 *
 * <p>列表按数据级权限过滤(村管只看本村),待受理的由 SQL 排在前面 ——
 * 管理员靠这个队列发现新案件,系统不额外群发通知给管理员。
 */
@RestController
@RequestMapping("/api/admin/dispute")
@RequiredArgsConstructor
@Tag(name = "后台-纠纷处理", description = "本村交易纠纷的调解与结论")
public class AdminDisputeController {

    private final BizDisputeService disputeService;

    @GetMapping("/list")
    @RequiresPerm("dispute:handle")
    @Operation(summary = "纠纷列表",
            description = "受数据级权限约束。待受理(0)和处理中(1)的自动排在前面")
    public Result<PageResult<DisputeVO>> list(DisputeQueryDTO query) {
        return Result.success(disputeService.pageAdmin(query));
    }

    @GetMapping("/{id}")
    @RequiresPerm("dispute:handle")
    @Operation(summary = "纠纷详情",
            description = "含申请人/被申请人联系方式、订单摘要、证据图片、"
                    + "处理结论,以及服务端算好的 canHandle / canCancel")
    public Result<DisputeVO> detail(@PathVariable Long id) {
        return Result.success(disputeService.getDetail(id));
    }

    @PutMapping("/{id}/handle")
    @RequiresPerm("dispute:handle")
    @Log(module = "纠纷处理", operation = "调解结案")
    @Operation(summary = "处理纠纷",
            description = "status:2 已调解 3 已关闭。结论写入的同时订单会恢复到"
                    + "申请前的状态(2 待交付 或 3 使用中),双方收到站内信。"
                    + "三件事在同一事务内 —— 只改纠纷不恢复订单会让订单永久卡在"
                    + "「纠纷中」,而再申请一次会被「已有未决纠纷」挡住")
    public Result<Void> handle(@PathVariable Long id, @Valid @RequestBody DisputeHandleDTO dto) {
        disputeService.handle(id, dto);
        return Result.success("已处理,双方已收到通知", null);
    }
}
