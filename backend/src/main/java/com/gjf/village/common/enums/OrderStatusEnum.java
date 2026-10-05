package com.gjf.village.common.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 订单状态枚举 —— 本模块的状态机定义。
 *
 * <pre>
 *   租赁:0 待卖家确认 → 1 待付款 → 2 待交付 → 3 使用中 → 4 已完成
 *   购买:0 待卖家确认 → 1 待付款 → 2 待交付 → 4 已完成
 *
 *   旁支:0/1 → 5 已取消(买家)   0 → 7 卖家已拒绝   2/3 → 6 纠纷中
 * </pre>
 *
 * <p><b>为什么状态迁移规则写在枚举里而不是散在 Service 的 if 里:</b>
 * 订单状态有 8 个取值,如果每个操作各自写 {@code if (status == 1)}
 * 去判断能不能执行,那么"这个状态下到底允许哪些动作"这个问题
 * 就没有任何一处能回答 —— 得把整个 Service 读完才拼得出来。
 * 收敛到枚举后,状态机是一张能一眼看完的表,加状态时也不会漏改。
 *
 * <p>另有一条贯穿全模块的规则用 {@link #occupiesSchedule()} 表达:
 * <b>哪些订单算"占着档期"</b>。这直接决定了时段冲突检测的口径。
 */
@Getter
public enum OrderStatusEnum {

    /** 买家已下单,等卖家确认接单 */
    PENDING_CONFIRM(0, "待卖家确认"),

    /** 卖家已接单,等买家付款 */
    PENDING_PAY(1, "待付款"),

    /** 已付款,等卖家交付(租赁:等交付实物;购买:等买家收货) */
    PAID(2, "待交付"),

    /** 卖家已交付,买家在使用中(仅租赁) */
    IN_USE(3, "使用中"),

    /** 订单结束,资金与信用分均已结算 */
    FINISHED(4, "已完成"),

    /** 买家在付款前取消 */
    CANCELED(5, "已取消"),

    /** 已付款后产生争议,由村级管理员协调 */
    DISPUTE(6, "纠纷中"),

    /** 卖家拒绝接单 */
    REJECTED(7, "卖家已拒绝");

    private final Integer code;
    private final String description;

    OrderStatusEnum(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public static OrderStatusEnum of(Integer code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElse(PENDING_CONFIRM);
    }

    // ═══════════════════════════════════════════════════════════
    //  状态机:当前状态允许哪些动作
    // ═══════════════════════════════════════════════════════════

    /** 卖家能否确认接单 */
    public boolean canConfirm() {
        return this == PENDING_CONFIRM;
    }

    /** 卖家能否拒绝接单 */
    public boolean canReject() {
        return this == PENDING_CONFIRM;
    }

    /** 买家能否付款 */
    public boolean canPay() {
        return this == PENDING_PAY;
    }

    /**
     * 买家能否取消。
     *
     * <p>只允许在付款前取消 —— 付款之后钱已经进了卖家账户,
     * 想撤回必须走纠纷流程由管理员调解,不能由买家单方面操作。
     */
    public boolean canCancel() {
        return this == PENDING_CONFIRM || this == PENDING_PAY;
    }

    /** 卖家能否交付(租赁:交出实物;购买:发货) */
    public boolean canDeliver() {
        return this == PAID;
    }

    /** 买家能否确认收货(仅购买类,交付后直接完结,没有"使用中"环节) */
    public boolean canReceive() {
        return this == PAID;
    }

    /** 买家能否归还(仅租赁类) */
    public boolean canReturn() {
        return this == IN_USE;
    }

    // ═══════════════════════════════════════════════════════════
    //  档期占用
    // ═══════════════════════════════════════════════════════════

    /**
     * 该状态的订单是否占用资源的租赁档期。
     *
     * <p><b>这是时段冲突检测的核心口径。</b>已取消(5)和卖家已拒绝(7)
     * 的订单不占用档期 —— 它们从没真正成立过,不该继续挡住后来的买家。
     * 如果不排除这两种状态,一个被拒绝的订单会永久锁死那段时间,
     * 发布者只能眼睁睁看着资源空置。
     *
     * <p>已完成(4)的订单<b>仍然算占用</b>:它的租期落在过去,
     * 正常情况下不会和未来的租期重叠;一旦重叠说明数据有问题,
     * 此时宁可报冲突让人来查,也好过悄悄放行造成双份合同。
     */
    public boolean occupiesSchedule() {
        return this != CANCELED && this != REJECTED;
    }

    /** 是否终态(不再发生任何流转) */
    public boolean isFinal() {
        return this == FINISHED || this == CANCELED || this == REJECTED;
    }
}
