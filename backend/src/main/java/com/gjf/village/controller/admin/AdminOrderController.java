package com.gjf.village.controller.admin;

import com.gjf.village.annotation.Log;
import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.OrderCancelDTO;
import com.gjf.village.dto.OrderQueryDTO;
import com.gjf.village.service.BizOrderService;
import com.gjf.village.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 后台 —— 订单管理。
 *
 * <p>村级管理员看本村订单、超管看全部,靠 Service 层的
 * {@code dataScopeHelper.apply()} 注入村组条件实现。
 * 前端传的 villageId 会被无条件覆盖。
 */
@RestController
@RequestMapping("/api/admin/order")
@RequiredArgsConstructor
@Tag(name = "后台-订单管理", description = "本村订单查询与强制取消")
public class AdminOrderController {

    private final BizOrderService orderService;

    @GetMapping("/list")
    @RequiresPerm("order:list")
    @Operation(summary = "订单列表",
            description = "村级管理员只能看到本村订单;可按状态、订单号、类型筛选")
    public Result<PageResult<OrderVO>> list(OrderQueryDTO query) {
        return Result.success(orderService.pageAdminOrders(query));
    }

    @PutMapping("/{id}/cancel")
    @RequiresPerm("order:cancel")
    @Log(module = "订单管理", operation = "强制取消订单")
    @Operation(summary = "强制取消订单",
            description = "用于纠纷前置处置,不受订单状态限制,但必须填写原因 —— 买卖双方都会收到该说明")
    public Result<Void> cancel(@PathVariable Long id, @RequestBody OrderCancelDTO dto) {
        orderService.adminCancel(id, dto == null ? null : dto.getReason());
        return Result.success("订单已强制取消", null);
    }
}
