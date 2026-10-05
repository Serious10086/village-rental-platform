package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gjf.village.common.enums.OrderStatusEnum;
import com.gjf.village.common.enums.ResourceStatusEnum;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.failpoint.TransactionFailPoint;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.ResultCode;
import com.gjf.village.common.util.BizNoGenerator;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.dto.OrderCreateDTO;
import com.gjf.village.dto.OrderQueryDTO;
import com.gjf.village.entity.*;
import com.gjf.village.mapper.*;
import com.gjf.village.security.LoginUser;
import com.gjf.village.service.BizOrderService;
import com.gjf.village.service.AiBehaviorService;
import com.gjf.village.service.CreditService;
import com.gjf.village.service.DataScopeHelper;
import com.gjf.village.service.MsgNoticeService;
import com.gjf.village.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 订单服务实现 —— 交易闭环的全部状态流转与记账。
 *
 * <p><b>本类集中了方案里四个事务边界,每一个都不是"写着好看":</b>
 *
 * <table border="1">
 *   <tr><th>事务</th><th>为什么必须原子</th></tr>
 *   <tr><td>下单</td><td>建订单成功而资源计数失败 = 数据不一致;
 *       先锁资源后失败 = 资源被永久锁死</td></tr>
 *   <tr><td>支付</td><td>订单状态改了但流水没写 = 钱没有凭证;
 *       卖家入账必须与订单状态同时变更</td></tr>
 *   <tr><td>完成</td><td>跨 4 张表 3 个概念(订单状态、信用分、资金、资源)。
 *       加分与写流水必须同时成功,只加分不记录就无法追溯</td></tr>
 *   <tr><td>取消</td><td>订单取消而资源没恢复上架 = 资源永久锁死</td></tr>
 * </table>
 *
 * <p>统一用 {@code rollbackFor = Exception.class}。Spring 默认只回滚
 * RuntimeException,不写这一句时,将来任何一处改成受检异常都会静默留下脏数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizOrderServiceImpl implements BizOrderService {

    private final BizOrderMapper orderMapper;
    private final BizResourceMapper resourceMapper;
    private final BizPaymentMapper paymentMapper;
    private final SysUserMapper userMapper;
    private final MsgNoticeService noticeService;
    private final CreditService creditService;
    private final DataScopeHelper dataScopeHelper;
    private final BizNoGenerator bizNoGenerator;
    private final TransactionFailPoint failPoint;
    private final AiBehaviorService behaviorService;

    /** 下单所需的最低信用分。低于这个值说明历史履约有问题 */
    private static final int MIN_CREDIT_SCORE = 60;

    /** 订单完成时双方各加的信用分 */
    private static final int CREDIT_ON_FINISH = 2;

    /** 实名状态:已认证 */
    private static final int REALNAME_VERIFIED = 2;

    /** 通知的 bizType:订单。与 msg_notice 的跳转规则保持一致 */
    private static final int NOTICE_BIZ_ORDER = 3;

    /** 支付方式:微信(模拟)/支付宝(模拟) */
    private static final int PAY_TYPE_WECHAT = 1;
    private static final int PAY_TYPE_ALIPAY = 2;

    private static final Map<Integer, String> TYPE_NAMES =
            Map.of(BizOrder.TYPE_RENT, "租赁", BizOrder.TYPE_BUY, "购买");

    private static final Map<Integer, String> PAY_STATUS_NAMES =
            Map.of(BizOrder.PAY_UNPAID, "未支付",
                    BizOrder.PAY_PAID, "已支付",
                    BizOrder.PAY_REFUNDED, "已退款");

    // ═══════════════════════════════════════════════════════════
    //  事务一:下单
    // ═══════════════════════════════════════════════════════════

    /**
     * 买家下单。
     *
     * <p><b>防超卖的关键在第一步:</b>{@code SELECT ... FOR UPDATE} 把
     * 同一个资源上的并发下单请求串行化。后到的请求必须等前一个事务提交
     * 才能读到资源行 —— 也才能看到对方刚插进去的订单。
     *
     * <p>如果省掉这把锁,时序会是这样:
     * <pre>
     *   T1: 读资源(已上架)         T2: 读资源(已上架)
     *   T1: 冲突检测 → 无冲突        T2: 冲突检测 → 无冲突
     *   T1: 插入订单                 T2: 插入订单      ← 同一时段两份合同
     * </pre>
     * 两个请求读到的都是"还没有任何人占用这个时段",于是双双放行。
     * 这就是典型的<b>检查后使用(TOCTOU)</b>竞态,锁是唯一能根治它的手段。
     *
     * <p>注意锁必须在<b>校验之前</b>获取。先校验再加锁等于没加锁:
     * 校验用的是加锁前的旧数据。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public Long create(OrderCreateDTO dto) {
        LoginUser user = UserContext.get();
        requireRealnameVerified(user);

        // ── ① 锁定资源行 ────────────────────────────────────────
        BizResource resource = resourceMapper.selectByIdForUpdate(dto.getResourceId());
        if (resource == null) {
            throw BusinessException.notFound("资源不存在或已下架");
        }

        // ── ② 资源归属与信用门槛 ────────────────────────────────
        if (Objects.equals(resource.getPublisherId(), user.getUserId())) {
            throw BusinessException.badRequest("不能租用或购买自己发布的资源");
        }
        // 信用分从库里现查,而不是用登录时签进 token 的值 ——
        // 信用分会随着订单完成而上涨,签在 token 里的那个是登录那一刻的快照,
        // 用户升级到 60 分后仍然会被挡住,直到重新登录
        requireCreditEnough(user.getUserId());

        // ── ③ 资源状态校验 ──────────────────────────────────────
        //
        // 排在金额计算之前:一个待审核的资源,用户需要听到的是
        // "它还没通过审核",而不是"你的租期超出可租窗口" ——
        // 后者会让人以为换个日期就能下单,白折腾一轮。
        ResourceStatusEnum resourceStatus = ResourceStatusEnum.of(resource.getResourceStatus());
        if (resourceStatus != ResourceStatusEnum.ONLINE) {
            // 出售类资源在有人下单的瞬间就被锁成"交易中",防止一物二卖。
            // 走到这里说明已经有人在买它了。
            throw new BusinessException(ResultCode.RESOURCE_STATUS_INVALID,
                    resourceStatus == ResourceStatusEnum.IN_TRADE
                            ? "该资源已有进行中的交易,请等待当前订单结束"
                            : "该资源当前" + resourceStatus.getDescription() + ",无法下单");
        }

        // ── ④ 金额与租期 ────────────────────────────────────────
        boolean isRent = Objects.equals(resource.getTradeType(), BizOrder.TYPE_RENT);
        OrderAmount amount = isRent
                ? calcRentAmount(resource, dto.getRentStartDate(), dto.getRentEndDate())
                : calcBuyAmount(resource);

        // ── ⑤ 时段冲突检测(租赁的核心门槛,必须在锁内) ─────────
        if (isRent) {
            int conflict = orderMapper.countConflictOrders(
                    resource.getId(), amount.rentStartDate(), amount.rentEndDate());
            if (conflict > 0) {
                throw new BusinessException(ResultCode.RENT_TIME_CONFLICT,
                        "所选租赁时段已被占用,请更换日期后重新提交");
            }
        }

        // ── ⑥ 高风险资源必须确认安全须知 ────────────────────────
        if (Integer.valueOf(1).equals(resource.getNeedTraining())
                && !Boolean.TRUE.equals(dto.getTrainingConfirmed())) {
            throw BusinessException.badRequest(
                    "该资源属于高风险设备,请先阅读并勾选确认安全使用须知");
        }

        // ── ⑥ 建订单 ────────────────────────────────────────────
        BizOrder order = new BizOrder();
        order.setOrderNo(bizNoGenerator.nextOrderNo());
        order.setOrderType(isRent ? BizOrder.TYPE_RENT : BizOrder.TYPE_BUY);
        order.setResourceId(resource.getId());
        order.setBuyerId(user.getUserId());
        order.setSellerId(resource.getPublisherId());
        // 订单归属资源所在村组,不接受前端传入 —— 否则可以下单到别的村,
        // 让该村管理员凭空多出一条管不着的订单
        order.setVillageId(resource.getVillageId());
        order.setRentStartDate(amount.rentStartDate());
        order.setRentEndDate(amount.rentEndDate());
        order.setRentDays(amount.rentDays());
        order.setUnitPrice(amount.unitPrice());
        order.setTotalAmount(amount.totalAmount());
        order.setDepositAmount(amount.depositAmount());
        order.setPayAmount(amount.payAmount());
        order.setOrderStatus(OrderStatusEnum.PENDING_CONFIRM.getCode());
        order.setPayStatus(BizOrder.PAY_UNPAID);
        order.setDeliveryType(dto.getDeliveryType() != null
                ? dto.getDeliveryType() : resource.getDeliveryType());
        order.setTrainingConfirmed(Boolean.TRUE.equals(dto.getTrainingConfirmed()) ? 1 : 0);
        order.setBuyerRemark(dto.getBuyerRemark());
        orderMapper.insert(order);

        // ── ⑦ 出售类资源下单即锁定 ──────────────────────────────
        //
        // 租赁和出售在这一点上必须分开处理,不能一刀切:
        //
        //   · 出售 —— 一台机器只有一个,卖出去就没了。有人下单就得先把
        //     它锁住,否则两个买家会同时持有对同一个物的有效合同。
        //
        //   · 租赁 —— 资源本身不会被"用掉",被占用的是某一段日期。
        //     9 月租给张三、11 月租给李四完全合理,把资源整体锁住
        //     反而让发布者每月只能做一单生意。所以租赁不改资源状态,
        //     由上面的档期冲突检测来把关。
        if (!isRent) {
            lockResource(resource.getId());
        }

        // ── ⑧ 资源被下单次数 +1(热度信号,用于"最受欢迎"排序) ──
        resourceMapper.update(null, new LambdaUpdateWrapper<BizResource>()
                .eq(BizResource::getId, resource.getId())
                .setSql("order_count = order_count + 1"));

        // ── ⑨ 通知卖家 ──────────────────────────────────────────
        noticeService.send(resource.getPublisherId(), MsgNotice.TYPE_ORDER,
                "你有新的订单待确认",
                "「" + resource.getResourceName() + "」收到一笔新订单(" + order.getOrderNo()
                        + "),应付 " + order.getPayAmount() + " 元,请及时确认接单。",
                NOTICE_BIZ_ORDER, order.getId());

        // ── ⑨.5 记一条下单行为(推荐用) ──────────────────────────
        // 位置是刻意的:在事务里、而且在故障注入点之前。
        // 这样"事务最后一步失败时前面全部回滚"那条用例(TC-04)
        // 顺带验证了行为记录也会一起回滚 —— 下单失败却在行为表里
        // 留下一条"他买过",会让推荐把他往那个方向带偏,
        // 而这种偏差在界面上完全看不出来。
        behaviorService.ordered(resource.getId());

        // ── ⑩ 故障注入点(仅测试触发,平时是空判断) ──────────────
        // 放在事务的最后一步,是为了验证"最后一步失败时前面全部回滚"
        failPoint.hit(TransactionFailPoint.CREATE_ORDER);

        log.info("下单成功: orderNo={}, resourceId={}, buyer={}, 金额={}(含押金 {}), 租期={}~{}",
                order.getOrderNo(), resource.getId(), user.getUserId(), order.getPayAmount(),
                order.getDepositAmount(), amount.rentStartDate(), amount.rentEndDate());
        return order.getId();
    }

    // ═══════════════════════════════════════════════════════════
    //  查询
    // ═══════════════════════════════════════════════════════════

    /**
     * 我买到的。
     *
     * <p>强制写入 buyerId —— 不接受前端传入,否则改一下就能看到别人的订单。
     * 同时清空 villageIds:买家看自己的订单,不该受村组限制
     * (他可能跨村租了邻村的农机)。
     */
    @Override
    public PageResult<OrderVO> pageMyPurchases(OrderQueryDTO query) {
        query.setBuyerId(UserContext.getUserId());
        query.setSellerId(null);
        query.setVillageIds(null);
        return pageOrders(query);
    }

    /** 我卖出的(卖家的"待我处理")。同样强制写入 sellerId */
    @Override
    public PageResult<OrderVO> pageMySales(OrderQueryDTO query) {
        query.setSellerId(UserContext.getUserId());
        query.setBuyerId(null);
        query.setVillageIds(null);
        return pageOrders(query);
    }

    /**
     * 后台订单管理。
     *
     * <p>这里注入数据范围:村级管理员只能看本村订单。
     * 同时清掉 buyerId/sellerId —— 后台看的是"本村的订单",
     * 不是"我参与的订单",带上这两个条件会把列表筛成空。
     */
    @Override
    public PageResult<OrderVO> pageAdminOrders(OrderQueryDTO query) {
        dataScopeHelper.apply(query);
        query.setBuyerId(null);
        query.setSellerId(null);
        return pageOrders(query);
    }

    private PageResult<OrderVO> pageOrders(OrderQueryDTO query) {
        Page<OrderDetailView> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<OrderDetailView> result = orderMapper.selectOrderPage(page, query);

        LoginUser user = UserContext.get();
        List<OrderVO> voList = result.getRecords().stream()
                .map(view -> toVO(view, user))
                .toList();
        return PageResult.of(result, voList);
    }

    /**
     * 订单详情。
     *
     * <p>三段式校验:是买家 → 放行;是卖家 → 放行;否则要求是
     * 有管辖权的管理员。都不满足就返回 404 而不是 403 ——
     * 403 等于确认了"这个ID确实有订单",可以被用来探测数据量。
     */
    @Override
    public OrderVO getDetail(Long id) {
        OrderDetailView view = orderMapper.selectOrderDetail(id);
        if (view == null) {
            throw BusinessException.notFound("订单不存在");
        }
        LoginUser user = UserContext.get();
        if (!canView(view, user)) {
            throw BusinessException.notFound("订单不存在");
        }
        return toVO(view, user);
    }

    private boolean canView(OrderDetailView view, LoginUser user) {
        if (Objects.equals(view.getBuyerId(), user.getUserId())
                || Objects.equals(view.getSellerId(), user.getUserId())) {
            return true;
        }
        return user.isSuperAdmin()
                || (user.getRoleCodes().contains("VILLAGE_ADMIN")
                    && dataScopeHelper.canAccessVillage(view.getVillageId()));
    }

    // ═══════════════════════════════════════════════════════════
    //  状态流转
    // ═══════════════════════════════════════════════════════════

    /**
     * 卖家确认接单。
     *
     * <p>不需要事务包太多东西,但<b>必须有一个</b>:状态变更与通知
     * 要么都成、要么都不成。这条在下面每个方法里都成立,
     * 所以宁可每个都标上,也不要靠"这个方法只有一个写操作"来判断 ——
     * 通知也是一次写操作,而且将来很可能再加一条。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void confirm(Long id) {
        BizOrder order = requireOrder(id);
        requireSeller(order);

        OrderStatusEnum status = OrderStatusEnum.of(order.getOrderStatus());
        if (!status.canConfirm()) {
            throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                    "订单当前状态为「" + status.getDescription() + "」,不能确认接单");
        }

        updateStatus(id, OrderStatusEnum.PENDING_CONFIRM, OrderStatusEnum.PENDING_PAY, update -> {
            update.set(BizOrder::getConfirmTime, LocalDateTime.now());
        });

        noticeService.send(order.getBuyerId(), MsgNotice.TYPE_ORDER,
                "卖家已确认接单,请尽快付款",
                "你的订单 " + order.getOrderNo() + " 已被卖家确认,应付金额 "
                        + order.getPayAmount() + " 元(含押金 " + order.getDepositAmount() + " 元)。",
                NOTICE_BIZ_ORDER, id);

        log.info("卖家确认接单: orderNo={}", order.getOrderNo());
    }

    /** 卖家拒绝接单:0 待确认 → 7 已拒绝 */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void reject(Long id, String reason) {
        BizOrder order = requireOrder(id);
        requireSeller(order);

        OrderStatusEnum status = OrderStatusEnum.of(order.getOrderStatus());
        if (!status.canReject()) {
            throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                    "订单当前状态为「" + status.getDescription() + "」,不能拒绝");
        }

        String finalReason = StringUtils.hasText(reason) ? reason : "卖家未说明原因";
        updateStatus(id, OrderStatusEnum.PENDING_CONFIRM, OrderStatusEnum.REJECTED, update -> {
            update.set(BizOrder::getCancelReason, finalReason);
            update.set(BizOrder::getFinishTime, LocalDateTime.now());
        });
        restoreResource(order.getResourceId());

        noticeService.send(order.getBuyerId(), MsgNotice.TYPE_ORDER,
                "卖家已拒绝你的订单",
                "订单 " + order.getOrderNo() + " 被卖家拒绝。原因:" + finalReason
                        + "。你可以看看同类的其他资源。",
                NOTICE_BIZ_ORDER, id);

        log.info("卖家拒绝接单: orderNo={}, 原因={}", order.getOrderNo(), finalReason);
    }

    /**
     * 买家付款(事务二)。
     *
     * <p><b>三笔账 + 一次状态变更,缺一不可:</b>
     * <ol>
     *   <li>订单状态 → 待交付、支付状态 → 已支付</li>
     *   <li>写买家的付款流水(这笔钱的凭证)</li>
     *   <li>卖家入账 + 写结算流水</li>
     *   <li>资源置为"交易中",从货架上撤下</li>
     * </ol>
     *
     * <p>卖家入账用的是 {@code UPDATE balance = balance + ?} 而不是
     * "查出来加完再写回"。后者在并发下会丢更新 ——
     * 详见 {@code SysUserMapper#increaseBalance} 的注释。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void pay(Long id, Integer payType) {
        BizOrder order = requireOrder(id);
        requireBuyer(order);

        OrderStatusEnum status = OrderStatusEnum.of(order.getOrderStatus());
        if (!status.canPay()) {
            throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                    "订单当前状态为「" + status.getDescription() + "」,不能支付");
        }

        // 资源必须是"可交易"状态。这里刻意<b>不</b>要求它处于"已上架":
        // 出售类资源在有人下单的那一刻就已经被锁成了"交易中",
        // 而那正是本订单自己造成的 —— 要求 ONLINE 会让买家付不了自己的款。
        // 真正要拦的是"管理员把资源违规下架了,买家还在付款"这种情况。
        BizResource resource = resourceMapper.selectById(order.getResourceId());
        if (resource == null || !isTradable(resource.getResourceStatus())) {
            throw new BusinessException(ResultCode.RESOURCE_STATUS_INVALID,
                    "该资源已被下架或不可交易,请联系发布者");
        }

        int finalPayType = (payType == null) ? PAY_TYPE_WECHAT : payType;
        if (finalPayType != PAY_TYPE_WECHAT && finalPayType != PAY_TYPE_ALIPAY) {
            throw BusinessException.badRequest("不支持的支付方式");
        }
        LocalDateTime now = LocalDateTime.now();

        // ── ① 订单状态 ──────────────────────────────────────────
        updateStatus(id, OrderStatusEnum.PENDING_PAY, OrderStatusEnum.PAID, update -> {
            update.set(BizOrder::getPayStatus, BizOrder.PAY_PAID);
            update.set(BizOrder::getPayTime, now);
        });

        // ── ② 买家付款流水 ──────────────────────────────────────
        String channelTradeNo = bizNoGenerator.nextChannelTradeNo();
        writePayment(order, BizOrder.PAY_PAID, order.getBuyerId(), finalPayType,
                order.getPayAmount(), now, channelTradeNo,
                finalPayType == PAY_TYPE_WECHAT ? "微信支付(模拟)" : "支付宝(模拟)");

        // ── ③ 卖家入账 + 结算流水 ───────────────────────────────
        // 只有租金/货款进卖家余额,押金不进 —— 押金是平台代管的,
        // 订单完成后原路退还买家。混在一起会让卖家误以为可以提走。
        userMapper.increaseBalance(order.getSellerId(), order.getTotalAmount());
        writePayment(order, BizPayment.STATUS_SUCCESS, order.getSellerId(),
                BizPayment.TYPE_SETTLEMENT, order.getTotalAmount(), now, null,
                "订单 " + order.getOrderNo() + " 货款结算");

        // ── ④ 通知双方 ──────────────────────────────────────────
        // 注意这里<b>不改资源状态</b>。租赁资源的可用性由档期决定,
        // 一笔付款不该把它从货架上撤下来 —— 9 月租出去的机器,
        // 11 月照样该能被别人预订。
        noticeService.send(order.getSellerId(), MsgNotice.TYPE_ORDER,
                "买家已付款",
                "订单 " + order.getOrderNo() + " 买家已付款 " + order.getPayAmount()
                        + " 元," + order.getTotalAmount()
                        + " 元已入账到你的余额。请按约定时间交付。",
                NOTICE_BIZ_ORDER, id);

        log.info("订单支付成功: orderNo={}, 支付方式={}, 含押金总额={}, 卖家入账={}",
                order.getOrderNo(), finalPayType, order.getPayAmount(), order.getTotalAmount());
    }

    /** 卖家交付:2 待交付 → 3 使用中(仅租赁) */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void deliver(Long id) {
        BizOrder order = requireOrder(id);
        requireSeller(order);

        OrderStatusEnum status = OrderStatusEnum.of(order.getOrderStatus());
        if (!status.canDeliver()) {
            throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                    "订单当前状态为「" + status.getDescription() + "」,不能交付");
        }
        if (!Objects.equals(order.getOrderType(), BizOrder.TYPE_RENT)) {
            // 购买类没有"使用中"环节:卖家发出后由买家确认收货,一步到完成
            throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                    "购买类订单无需交付操作,请等待买家确认收货");
        }

        updateStatus(id, OrderStatusEnum.PAID, OrderStatusEnum.IN_USE, update -> {
            update.set(BizOrder::getDeliverTime, LocalDateTime.now());
        });

        noticeService.send(order.getBuyerId(), MsgNotice.TYPE_ORDER,
                "卖家已交付,资源使用中",
                "订单 " + order.getOrderNo() + " 的资源已交付,现在进入使用期("
                        + order.getRentStartDate() + " 至 " + order.getRentEndDate()
                        + ")。使用完毕请点击「确认归还」。",
                NOTICE_BIZ_ORDER, id);

        log.info("卖家交付: orderNo={}", order.getOrderNo());
    }

    /** 买家确认收货(购买类):2 待交付 → 4 已完成。事务三 */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void receive(Long id) {
        BizOrder order = requireOrder(id);
        requireBuyer(order);

        OrderStatusEnum status = OrderStatusEnum.of(order.getOrderStatus());
        if (!status.canReceive()) {
            throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                    "订单当前状态为「" + status.getDescription() + "」,不能确认收货");
        }
        if (Objects.equals(order.getOrderType(), BizOrder.TYPE_RENT)) {
            throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                    "租赁订单请使用「确认归还」");
        }
        finishOrder(order, "买家确认收货");
    }

    /** 买家确认归还(租赁类):3 使用中 → 4 已完成。事务三 */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void confirmReturn(Long id) {
        BizOrder order = requireOrder(id);
        requireBuyer(order);

        OrderStatusEnum status = OrderStatusEnum.of(order.getOrderStatus());
        if (!status.canReturn()) {
            throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                    "订单当前状态为「" + status.getDescription() + "」,不能确认归还");
        }
        finishOrder(order, "资源已归还");
    }

    /**
     * 订单完成(事务三,全系统最复杂的一处)。
     *
     * <p>一次事务改 4 张表、跨 3 个概念:
     * <pre>
     *   订单状态  → 已完成 + 归还时间 + 完成时间
     *   资金      → 押金退还流水(买家的钱回到买家手里)
     *   信用分    → 双方各 +2,并各写一条带前后值的流水
     *   资源      → 租赁恢复上架 / 出售置为已完成
     * </pre>
     *
     * <p><b>为什么信用分和流水必须同生共死:</b>只加分不写流水,
     * 用户问"这 2 分哪来的"就查不到;只写流水不加分,用户看到的是
     * 一本对不上的账。两者分开写还会在失败时留下一个假象 ——
     * 流水记录了加分,分数却没变。
     *
     * <p>押金退还这里<b>不写买家余额</b>:押金走的是模拟支付渠道,
     * 从没进过平台余额,退了也是原路出渠道。流水的作用是留凭证。
     */
    private void finishOrder(BizOrder order, String reason) {
        Long id = order.getId();
        LocalDateTime now = LocalDateTime.now();

        updateStatus(id, OrderStatusEnum.of(order.getOrderStatus()),
                OrderStatusEnum.FINISHED, update -> {
            update.set(BizOrder::getFinishTime, now);
            // 购买类没有租期,归还时间无意义,留空
            if (Objects.equals(order.getOrderType(), BizOrder.TYPE_RENT)) {
                update.set(BizOrder::getReturnTime, now);
            }
        });

        // ── 押金退还 ────────────────────────────────────────────
        if (order.getDepositAmount() != null
                && order.getDepositAmount().compareTo(BigDecimal.ZERO) > 0) {
            writePayment(order, BizPayment.STATUS_SUCCESS, order.getBuyerId(),
                    BizPayment.TYPE_DEPOSIT_REFUND, order.getDepositAmount(), now, null,
                    "订单 " + order.getOrderNo() + " 完成,押金原路退还");
        }

        // ── 信用分:买卖双方各 +2,各写一条流水 ──────────────────
        //
        // 走 CreditService 而不是在这里自己改分数:信用分的变更来源
        // 不止订单完成这一处(评价、纠纷判罚、管理员手动调整都会改它),
        // 各自为政的话,"分数变了但流水没写"这类不一致迟早出现。
        String creditReason = "订单 " + order.getOrderNo() + " " + reason + ",按时履约";
        creditService.adjust(order.getBuyerId(), CREDIT_ON_FINISH, creditReason,
                BizCreditRecord.BIZ_ORDER, id, order.getVillageId());
        creditService.adjust(order.getSellerId(), CREDIT_ON_FINISH, creditReason,
                BizCreditRecord.BIZ_ORDER, id, order.getVillageId());

        // ── 资源状态 ────────────────────────────────────────────
        if (Objects.equals(order.getOrderType(), BizOrder.TYPE_RENT)) {
            restoreResource(order.getResourceId());
        } else {
            // 出售类资源卖掉即终态,不再上架
            resourceMapper.update(null, new LambdaUpdateWrapper<BizResource>()
                    .eq(BizResource::getId, order.getResourceId())
                    .eq(BizResource::getResourceStatus, ResourceStatusEnum.IN_TRADE.getCode())
                    .set(BizResource::getResourceStatus, ResourceStatusEnum.FINISHED.getCode()));
        }

        // ── 通知双方 ────────────────────────────────────────────
        String content = "订单 " + order.getOrderNo() + " 已完成。" + reason
                + ",双方信用分各 +" + CREDIT_ON_FINISH + "。";
        if (order.getDepositAmount() != null
                && order.getDepositAmount().compareTo(BigDecimal.ZERO) > 0) {
            content += "押金 " + order.getDepositAmount() + " 元已原路退还。";
        }
        noticeService.send(order.getBuyerId(), MsgNotice.TYPE_ORDER,
                "订单已完成", content, NOTICE_BIZ_ORDER, id);
        noticeService.send(order.getSellerId(), MsgNotice.TYPE_ORDER,
                "订单已完成", content, NOTICE_BIZ_ORDER, id);

        log.info("订单完成: orderNo={}, 原因={}, 押金退还={}, 双方信用 +{}",
                order.getOrderNo(), reason, order.getDepositAmount(), CREDIT_ON_FINISH);
    }

    /**
     * 买家取消订单(事务四)。
     *
     * <p>只允许在付款前取消。付款之后钱已经进了卖家余额,
     * 买家单方面撤回等于凭空转移资金,必须走纠纷流程由管理员调解。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void cancel(Long id, String reason) {
        BizOrder order = requireOrder(id);
        requireBuyer(order);

        OrderStatusEnum status = OrderStatusEnum.of(order.getOrderStatus());
        if (!status.canCancel()) {
            throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                    status.isFinal()
                            ? "订单已「" + status.getDescription() + "」,无需取消"
                            : "订单已付款,不能直接取消。如有争议请申请纠纷协调");
        }

        String finalReason = StringUtils.hasText(reason) ? reason : "买家主动取消";
        updateStatus(id, status, OrderStatusEnum.CANCELED, update -> {
            update.set(BizOrder::getCancelReason, finalReason);
            update.set(BizOrder::getFinishTime, LocalDateTime.now());
        });
        restoreResource(order.getResourceId());

        noticeService.send(order.getSellerId(), MsgNotice.TYPE_ORDER,
                "买家已取消订单",
                "订单 " + order.getOrderNo() + " 已被买家取消。原因:" + finalReason,
                NOTICE_BIZ_ORDER, id);

        log.info("买家取消订单: orderNo={}, 原因={}", order.getOrderNo(), finalReason);
    }

    /**
     * 管理员强制取消。
     *
     * <p>用于纠纷的前置处置:资源被封、卖家账号异常这类情况下,
     * 订单必须能被终止,否则双方都卡在那里。
     * 与买家取消的差别是<b>不受状态限制</b> —— 管理员可以做买家做不到的事,
     * 但这正是要有权限控制的原因。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void adminCancel(Long id, String reason) {
        BizOrder order = requireOrder(id);
        LoginUser user = UserContext.get();
        if (!user.isSuperAdmin()
                && !(user.getRoleCodes().contains("VILLAGE_ADMIN")
                     && dataScopeHelper.canAccessVillage(order.getVillageId()))) {
            throw BusinessException.forbidden("只能处理本村的订单");
        }

        OrderStatusEnum status = OrderStatusEnum.of(order.getOrderStatus());
        if (status.isFinal()) {
            throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                    "订单已是「" + status.getDescription() + "」,无需处理");
        }
        if (!StringUtils.hasText(reason)) {
            throw BusinessException.badRequest("请填写强制取消的原因,双方都会收到这条说明");
        }

        updateStatus(id, status, OrderStatusEnum.CANCELED, update -> {
            update.set(BizOrder::getCancelReason, "管理员处理:" + reason);
            update.set(BizOrder::getFinishTime, LocalDateTime.now());
        });
        restoreResource(order.getResourceId());

        String content = "订单 " + order.getOrderNo() + " 已被管理员取消。原因:" + reason;
        noticeService.send(order.getBuyerId(), MsgNotice.TYPE_ORDER,
                "订单已被取消", content, NOTICE_BIZ_ORDER, id);
        noticeService.send(order.getSellerId(), MsgNotice.TYPE_ORDER,
                "订单已被取消", content, NOTICE_BIZ_ORDER, id);

        log.info("管理员强制取消订单: orderNo={}, 操作人={}, 原因={}",
                order.getOrderNo(), user.getUserId(), reason);
    }

    // ═══════════════════════════════════════════════════════════
    //  私有:金额计算
    // ═══════════════════════════════════════════════════════════

    /** 一次下单算出来的全部金额与租期 */
    private record OrderAmount(LocalDate rentStartDate, LocalDate rentEndDate,
                               Integer rentDays, BigDecimal unitPrice,
                               BigDecimal totalAmount, BigDecimal depositAmount,
                               BigDecimal payAmount) {
    }

    /**
     * 租赁类的金额与租期。
     *
     * <p>租期天数<b>含头含尾</b>:9 月 1 日租到 9 月 3 日算 3 天。
     * 用 {@code ChronoUnit.DAYS.between} 直接算会得到 2 —— 它算的是
     * 两个日期之间隔了几天,不是"占用了几天"。这个差 1 很容易漏,
     * 漏了就是每天少收一天租金。
     *
     * <p>计价单位不是"元/天"时(元/次、元/件),总额就是单价本身,
     * 乘天数反而算错。
     */
    private OrderAmount calcRentAmount(BizResource resource, LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw BusinessException.badRequest("请选择租赁的起止日期");
        }
        if (end.isBefore(start)) {
            throw BusinessException.badRequest("租期结束日不能早于开始日");
        }
        if (resource.getRentStartDate() != null && start.isBefore(resource.getRentStartDate())) {
            throw BusinessException.badRequest(
                    "该资源最早可租日期为 " + resource.getRentStartDate());
        }
        if (resource.getRentEndDate() != null && end.isAfter(resource.getRentEndDate())) {
            throw BusinessException.badRequest(
                    "该资源最晚可租至 " + resource.getRentEndDate());
        }

        long days = ChronoUnit.DAYS.between(start, end) + 1;
        BigDecimal unitPrice = resource.getPrice();
        boolean byDay = resource.getPriceUnit() == null || resource.getPriceUnit() == 1;
        BigDecimal total = byDay ? unitPrice.multiply(BigDecimal.valueOf(days)) : unitPrice;
        BigDecimal deposit = resource.getDeposit() == null ? BigDecimal.ZERO : resource.getDeposit();

        return new OrderAmount(start, end, (int) days, unitPrice, total, deposit,
                total.add(deposit));
    }

    /** 购买类:总额即售价,没有押金、没有租期 */
    private OrderAmount calcBuyAmount(BizResource resource) {
        BigDecimal price = resource.getPrice();
        return new OrderAmount(null, null, null, price, price, BigDecimal.ZERO, price);
    }

    // ═══════════════════════════════════════════════════════════
    //  私有:记账与状态
    // ═══════════════════════════════════════════════════════════

    /**
     * 写一条资金流水。
     *
     * <p>流水是只增不改的账目,所以这里只有 insert,没有任何更新路径。
     */
    private void writePayment(BizOrder order, int status, Long userId, int payType,
                              BigDecimal amount, LocalDateTime time,
                              String channelTradeNo, String remark) {
        BizPayment payment = new BizPayment();
        payment.setPayNo(bizNoGenerator.nextPaymentNo());
        payment.setOrderId(order.getId());
        payment.setUserId(userId);
        payment.setPayType(payType);
        payment.setAmount(amount);
        payment.setPayStatus(status);
        payment.setChannelTradeNo(channelTradeNo);
        payment.setPayTime(time);
        payment.setRemark(remark);
        payment.setVillageId(order.getVillageId());
        paymentMapper.insert(payment);
    }

    /**
     * 把出售类资源锁成"交易中"。
     *
     * <p>带 {@code resource_status = 1} 条件,只有确实还在架上时才锁 ——
     * 避免把管理员刚因违规下架的资源顺手改回可交易状态。
     */
    private void lockResource(Long resourceId) {
        resourceMapper.update(null, new LambdaUpdateWrapper<BizResource>()
                .eq(BizResource::getId, resourceId)
                .eq(BizResource::getResourceStatus, ResourceStatusEnum.ONLINE.getCode())
                .set(BizResource::getResourceStatus, ResourceStatusEnum.IN_TRADE.getCode()));
    }

    /**
     * 资源状态是否允许继续交易。
     *
     * <p>允许"已上架"(正常)和"交易中"(出售类被本订单自己锁住)。
     * 不允许待审核/临时下架/已完成/审核驳回/违规下架 ——
     * 尤其是违规下架:管理员已经判定这个资源不能交易了,
     * 不能因为买家手里有一张待付款的订单就绕过去。
     */
    private boolean isTradable(Integer resourceStatus) {
        return ResourceStatusEnum.ONLINE.getCode().equals(resourceStatus)
                || ResourceStatusEnum.IN_TRADE.getCode().equals(resourceStatus);
    }

    /**
     * 资源恢复上架。
     *
     * <p>条件是 {@code resource_status = 3}(交易中),这是<b>幂等保护</b>:
     * 只有确实被订单锁住的资源才需要恢复。如果无条件写成"置为上架",
     * 那么管理员刚因违规下架(6)的资源,会因为一笔订单的取消而被
     * 悄悄恢复到前台 —— 管理员的下架操作就白做了。
     */
    private void restoreResource(Long resourceId) {
        int rows = resourceMapper.update(null, new LambdaUpdateWrapper<BizResource>()
                .eq(BizResource::getId, resourceId)
                .eq(BizResource::getResourceStatus, ResourceStatusEnum.IN_TRADE.getCode())
                .set(BizResource::getResourceStatus, ResourceStatusEnum.ONLINE.getCode()));
        if (rows == 0) {
            log.debug("资源无需恢复上架(不处于交易中): resourceId={}", resourceId);
        }
    }

    /**
     * 带前置状态条件的状态更新。
     *
     * <p>{@code WHERE order_status = 期望的原状态} 是这里的要点:
     * 它让状态迁移变成<b>乐观锁</b>。如果两个请求同时点"确认接单",
     * 后一个的 UPDATE 会匹配 0 行 —— 因为它读到的原状态已经过期了。
     * 不带这个条件的话,两个请求会双双"成功",重复发两次通知。
     */
    private void updateStatus(Long id, OrderStatusEnum from, OrderStatusEnum to,
                              java.util.function.Consumer<LambdaUpdateWrapper<BizOrder>> extra) {
        LambdaUpdateWrapper<BizOrder> update = new LambdaUpdateWrapper<BizOrder>()
                .eq(BizOrder::getId, id)
                .eq(BizOrder::getOrderStatus, from.getCode())
                .set(BizOrder::getOrderStatus, to.getCode());
        if (extra != null) {
            extra.accept(update);
        }
        int rows = orderMapper.update(null, update);
        if (rows == 0) {
            throw new BusinessException(ResultCode.ORDER_STATUS_INVALID,
                    "订单状态已变更,请刷新后重试");
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  私有:取数与装配
    // ═══════════════════════════════════════════════════════════

    private BizOrder requireOrder(Long id) {
        BizOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw BusinessException.notFound("订单不存在");
        }
        return order;
    }

    private void requireBuyer(BizOrder order) {
        if (!Objects.equals(order.getBuyerId(), UserContext.getUserId())) {
            throw BusinessException.forbidden("只有买家本人可以执行该操作");
        }
    }

    private void requireSeller(BizOrder order) {
        if (!Objects.equals(order.getSellerId(), UserContext.getUserId())) {
            throw BusinessException.forbidden("只有卖家本人可以执行该操作");
        }
    }

    private void requireRealnameVerified(LoginUser user) {
        if (!Objects.equals(user.getRealnameStatus(), REALNAME_VERIFIED)) {
            throw new BusinessException(ResultCode.REALNAME_REQUIRED,
                    "请先完成实名认证后再下单");
        }
    }

    /** 校验买家信用分是否达到下单门槛 */
    private void requireCreditEnough(Long userId) {
        SysUser buyer = userMapper.selectById(userId);
        if (buyer == null) {
            throw BusinessException.notFound("账号不存在");
        }
        int credit = buyer.getCreditScore() == null ? 100 : buyer.getCreditScore();
        if (credit < MIN_CREDIT_SCORE) {
            throw new BusinessException(ResultCode.CREDIT_TOO_LOW,
                    "你的信用分为 " + credit + " 分,低于下单门槛 "
                            + MIN_CREDIT_SCORE + " 分,暂时无法下单");
        }
    }

    /**
     * 装配展示对象:补中文名、身份、可执行动作。
     *
     * <p>动作清单由后端算,前端只认动作码 —— 状态机的唯一定义处
     * 留在 {@code OrderStatusEnum},前端不用跟着改。
     */
    private OrderVO toVO(OrderDetailView view, LoginUser user) {
        OrderVO vo = new OrderVO();
        org.springframework.beans.BeanUtils.copyProperties(view, vo);

        OrderStatusEnum status = OrderStatusEnum.of(view.getOrderStatus());
        vo.setOrderStatusName(status.getDescription());
        vo.setOrderTypeName(TYPE_NAMES.getOrDefault(view.getOrderType(), "未知"));
        vo.setPayStatusName(PAY_STATUS_NAMES.getOrDefault(view.getPayStatus(), "未知"));

        boolean isBuyer = Objects.equals(view.getBuyerId(), user.getUserId());
        boolean isSeller = Objects.equals(view.getSellerId(), user.getUserId());
        vo.setMyRole(isBuyer ? "buyer" : isSeller ? "seller" : "admin");
        vo.setActions(resolveActions(view, status, isBuyer, isSeller));
        return vo;
    }

    private List<String> resolveActions(OrderDetailView view, OrderStatusEnum status,
                                        boolean isBuyer, boolean isSeller) {
        List<String> actions = new ArrayList<>();
        boolean isRent = Objects.equals(view.getOrderType(), BizOrder.TYPE_RENT);

        if (isBuyer) {
            if (status.canPay()) {
                actions.add(OrderVO.OrderAction.PAY);
            }
            if (status.canCancel()) {
                actions.add(OrderVO.OrderAction.CANCEL);
            }
            if (isRent && status.canReturn()) {
                actions.add(OrderVO.OrderAction.RETURN);
            }
            if (!isRent && status.canReceive()) {
                actions.add(OrderVO.OrderAction.RECEIVE);
            }
        }
        if (isSeller) {
            if (status.canConfirm()) {
                actions.add(OrderVO.OrderAction.CONFIRM);
                actions.add(OrderVO.OrderAction.REJECT);
            }
            // 购买类没有交付环节,卖家在"待交付"状态下没有可做的动作
            if (isRent && status.canDeliver()) {
                actions.add(OrderVO.OrderAction.DELIVER);
            }
        }
        return actions;
    }

    /** 按状态统计(供后台工作台用) */
    public long countByStatus(Integer status) {
        return orderMapper.selectCount(new LambdaQueryWrapper<BizOrder>()
                .eq(status != null, BizOrder::getOrderStatus, status));
    }
}
