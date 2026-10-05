package com.gjf.village.controller.portal;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.DisputeApplyDTO;
import com.gjf.village.dto.DisputeQueryDTO;
import com.gjf.village.service.BizDisputeService;
import com.gjf.village.vo.DisputeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 纠纷接口(前台)。
 *
 * <p>前台只做两件事:<b>申请</b>和<b>撤销</b>。给出结论是管理员的活,
 * 所以这里没有"处理纠纷"的方法 —— 权限边界在接口划分上就是可见的。
 *
 * <p><b>关于争议的后果:</b>纠纷本身不动钱、不改信用分。调解结果要退款
 * 就走管理员那边的「强制取消订单」,要扣分就走「信用分调整」。
 * 这两个动作都已经有带完整状态校验和流水记录的实现,
 * 在这里再写一套只会让同一件事有两个口径。
 */
@RestController
@RequestMapping("/api/dispute")
@RequiredArgsConstructor
@Tag(name = "交易纠纷", description = "申请纠纷、撤销纠纷、查看与我有关的纠纷")
public class DisputeController {

    private final BizDisputeService disputeService;

    @PostMapping("/apply")
    @Operation(summary = "申请纠纷",
            description = "只有已付款且未完成的订单(状态 2 待交付 / 3 使用中)可以申请。"
                    + "服务端把订单置为「纠纷中」并把原状态存档,调解后据此复原。"
                    + "被申请人由服务端根据订单推出,不接受前端传入")
    public Result<Long> apply(@Valid @RequestBody DisputeApplyDTO dto) {
        return Result.success("纠纷已提交,管理员会尽快调解", disputeService.apply(dto));
    }

    @PutMapping("/{id}/cancel")
    @Operation(summary = "撤销纠纷",
            description = "只有申请人本人、且管理员尚未处理时可以撤销。"
                    + "撤销后订单恢复到申请前的状态")
    public Result<Void> cancel(@PathVariable Long id) {
        disputeService.cancel(id);
        return Result.success("已撤销,订单恢复正常流程", null);
    }

    @GetMapping("/mine")
    @Operation(summary = "与我有关的纠纷",
            description = "我是申请人或被申请人两种情况都返回。"
                    + "这个列表刻意不按村组过滤 —— 外来用户没有归属村,"
                    + "按村组过滤会让他连自己被告了的纠纷都看不到")
    public Result<PageResult<DisputeVO>> mine(DisputeQueryDTO query) {
        return Result.success(disputeService.pageMine(query));
    }

    @GetMapping("/order/{orderId}")
    @Operation(summary = "某笔订单的纠纷记录",
            description = "订单详情页据此决定显示「申请纠纷」还是「查看纠纷」")
    public Result<List<DisputeVO>> byOrder(@PathVariable Long orderId) {
        return Result.success(disputeService.listByOrder(orderId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "纠纷详情",
            description = "仅申请人、被申请人、有权管辖的管理员可见")
    public Result<DisputeVO> detail(@PathVariable Long id) {
        return Result.success(disputeService.getDetail(id));
    }
}
