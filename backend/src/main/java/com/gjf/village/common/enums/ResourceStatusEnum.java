package com.gjf.village.common.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 资源状态枚举。
 *
 * <p>用枚举而不是散落各处的魔法数字 —— "为什么这里判断 == 1"这种问题,
 * 读代码的人不该被迫去翻数据库注释。
 */
@Getter
public enum ResourceStatusEnum {

    /** 刚发布,等待村级管理员审核 */
    PENDING(0, "待审核"),

    /** 审核通过,前台可见可下单 */
    ONLINE(1, "已上架"),

    /** 发布者主动下架,可随时重新上架 */
    OFFLINE(2, "临时下架"),

    /** 已被订单锁定,不能再被别人下单 */
    IN_TRADE(3, "交易中"),

    /** 出售类资源成交后的终态 */
    FINISHED(4, "已完成"),

    /** 管理员审核未通过 */
    REJECTED(5, "审核驳回"),

    /** 管理员强制下架(违规或安全隐患) */
    VIOLATED(6, "违规下架");

    private final Integer code;
    private final String description;

    ResourceStatusEnum(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public static ResourceStatusEnum of(Integer code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElse(PENDING);
    }

    /**
     * 是否处于"可被买家看到"的状态。
     *
     * <p>这跟 v_resource_list 视图里的 {@code resource_status = 1} 是同一个判断,
     * 保持两处一致 —— 视图负责数据库层面的过滤,这个方法负责业务代码里的判断。
     */
    public boolean isVisibleToPublic() {
        return this == ONLINE;
    }

    /**
     * 发布者能否修改内容。
     *
     * <p>交易中(3)的资源不能改 —— 买家已经基于当前信息下单了,
     * 这时候改价格或改描述属于单方面变更合同。
     */
    public boolean isEditable() {
        return this == PENDING || this == ONLINE
                || this == OFFLINE || this == REJECTED;
    }

    /**
     * 发布者能否主动下架。
     */
    public boolean isOfflinable() {
        return this == ONLINE;
    }

    /**
     * 发布者能否重新上架。
     *
     * <p>只有"临时下架"能自己上架回来;"审核驳回"和"违规下架"必须重新走审核,
     * 不能自己恢复 —— 否则管理员的下架操作就形同虚设。
     */
    public boolean isOnlinable() {
        return this == OFFLINE;
    }
}
