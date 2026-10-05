package com.gjf.village.controller.portal;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.Result;
import com.gjf.village.dto.OrderCancelDTO;
import com.gjf.village.dto.OrderCreateDTO;
import com.gjf.village.dto.OrderQueryDTO;
import com.gjf.village.service.BizOrderService;
import com.gjf.village.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 前台订单接口。
 *
 * <p>全部需要登录,且每个操作用户身份相关的接口都在 Service 层
 * 强制校验"你是不是这笔订单的买家/卖家"——
 * 接口层没有做任何身份限制,因为它做不了:同一个 URL
 * ({@code PUT /api/order/5/confirm})对不同的人意义不同,
 * 只有拿到订单数据之后才知道该不该放行。
 */
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
@Tag(name = "订单交易", description = "下单、支付、交付、归还、取消与订单查询")
public class OrderController {

    private final BizOrderService orderService;

    // ── 下单 ────────────────────────────────────────────────────

    @PostMapping("/create")
    @Operation(summary = "买家下单",
            description = "服务端锁资源行后做时段冲突检测,并计算金额。"
                    + "单价/总额/押金均不接受前端传入")
    public Result<Long> create(@Valid @RequestBody OrderCreateDTO dto) {
        return Result.success("下单成功,等待发布者确认", orderService.create(dto));
    }

    // ── 查询 ────────────────────────────────────────────────────

    @GetMapping("/mine")
    @Operation(summary = "我买到的(买家视角)")
    public Result<PageResult<OrderVO>> myPurchases(OrderQueryDTO query) {
        return Result.success(orderService.pageMyPurchases(query));
    }

    @GetMapping("/sold")
    @Operation(summary = "我卖出的(卖家视角)",
            description = "即村民的「待我处理」,返回别人租用/购买我发布的资源的订单")
    public Result<PageResult<OrderVO>> mySales(OrderQueryDTO query) {
        return Result.success(orderService.pageMySales(query));
    }

    @GetMapping("/detail/{id}")
    @Operation(summary = "订单详情",
            description = "仅买家、卖家、有管辖权的管理员可见;其余返回 404 而非 403")
    public Result<OrderVO> detail(@PathVariable Long id) {
        return Result.success(orderService.getDetail(id));
    }

    // ── 状态流转 ────────────────────────────────────────────────

    @PutMapping("/{id}/confirm")
    @Operation(summary = "卖家确认接单")
    public Result<Void> confirm(@PathVariable Long id) {
        orderService.confirm(id);
        return Result.success("已确认接单", null);
    }

    @PutMapping("/{id}/reject")
    @Operation(summary = "卖家拒绝接单", description = "必须填写原因")
    public Result<Void> reject(@PathVariable Long id, @RequestBody OrderCancelDTO dto) {
        orderService.reject(id, dto.getReason());
        return Result.success("已拒绝该订单", null);
    }

    @PutMapping("/{id}/pay")
    @Operation(summary = "买家付款(模拟)",
            description = "payType:1 微信(模拟) 2 支付宝(模拟)。"
                    + "付款后写资金流水、卖家入账、资源撤下货架,四件事在同一事务内完成")
    public Result<Void> pay(@PathVariable Long id,
                            @RequestParam(required = false) Integer payType) {
        orderService.pay(id, payType);
        return Result.success("支付成功", null);
    }

    @PutMapping("/{id}/deliver")
    @Operation(summary = "卖家交付(仅租赁)")
    public Result<Void> deliver(@PathVariable Long id) {
        orderService.deliver(id);
        return Result.success("已确认交付", null);
    }

    @PutMapping("/{id}/receive")
    @Operation(summary = "买家确认收货(仅购买)",
            description = "购买类没有「使用中」环节,确认收货即完成订单")
    public Result<Void> receive(@PathVariable Long id) {
        orderService.receive(id);
        return Result.success("已确认收货,订单完成", null);
    }

    @PutMapping("/{id}/return")
    @Operation(summary = "买家确认归还(仅租赁)",
            description = "归还即完结:退还押金、双方信用分 +2、资源恢复上架")
    public Result<Void> confirmReturn(@PathVariable Long id) {
        orderService.confirmReturn(id);
        return Result.success("已确认归还,订单完成", null);
    }

    @PutMapping("/{id}/cancel")
    @Operation(summary = "买家取消订单",
            description = "只允许在付款前取消。付款后需走纠纷协调流程")
    public Result<Void> cancel(@PathVariable Long id,
                               @RequestBody(required = false) OrderCancelDTO dto) {
        orderService.cancel(id, dto == null ? null : dto.getReason());
        return Result.success("订单已取消", null);
    }
}
