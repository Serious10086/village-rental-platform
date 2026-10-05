package com.gjf.village.service;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.dto.OrderCreateDTO;
import com.gjf.village.dto.OrderQueryDTO;
import com.gjf.village.vo.OrderVO;

/**
 * 订单服务 —— 交易域的主干。
 *
 * <p>接口上这十几个方法看着琐碎,但它们全部围绕同一张状态机展开:
 * 每个方法都是"当前状态 → 下一个状态"的一次迁移,外加该迁移
 * 必须一并完成的记账动作。把它们放在同一个 Service 而不是拆开,
 * 是因为状态机的完整性只有在同一处才能保证 —— 拆开之后
 * "支付时忘了写流水"这类漏洞就没有任何一层能拦住。
 */
public interface BizOrderService {

    // ── 下单 ────────────────────────────────────────────────────

    /**
     * 买家下单。
     *
     * <p>本方法是事务边界之一:锁资源行、校验、冲突检测、建订单、
     * 计数、通知,五件事必须同生共死。
     *
     * @return 新订单ID
     */
    Long create(OrderCreateDTO dto);

    // ── 查询 ────────────────────────────────────────────────────

    /** 我买到的(买家视角) */
    PageResult<OrderVO> pageMyPurchases(OrderQueryDTO query);

    /** 我卖出的(卖家视角,即"待我处理") */
    PageResult<OrderVO> pageMySales(OrderQueryDTO query);

    /** 后台订单管理(受数据级权限约束) */
    PageResult<OrderVO> pageAdminOrders(OrderQueryDTO query);

    /**
     * 订单详情。
     *
     * <p>可见性:买家、卖家本人,以及有权管辖该村的管理员。
     * 其余人一律 404 —— 订单里有手机号和金额,不能靠"猜不到ID"来保护。
     */
    OrderVO getDetail(Long id);

    // ── 状态流转 ────────────────────────────────────────────────

    /** 卖家确认接单:0 待确认 → 1 待付款 */
    void confirm(Long id);

    /** 卖家拒绝接单:0 待确认 → 7 已拒绝 */
    void reject(Long id, String reason);

    /** 买家付款:1 待付款 → 2 待交付。事务边界之二 */
    void pay(Long id, Integer payType);

    /** 卖家交付:2 待交付 → 3 使用中(仅租赁) */
    void deliver(Long id);

    /** 买家确认收货:2 待交付 → 4 已完成(仅购买)。事务边界之三 */
    void receive(Long id);

    /** 买家确认归还:3 使用中 → 4 已完成(仅租赁)。事务边界之三 */
    void confirmReturn(Long id);

    /** 买家取消订单:0/1 → 5 已取消。事务边界之四 */
    void cancel(Long id, String reason);

    /** 管理员强制取消订单(用于纠纷前置处置) */
    void adminCancel(Long id, String reason);
}
