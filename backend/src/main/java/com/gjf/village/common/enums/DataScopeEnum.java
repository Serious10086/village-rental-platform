package com.gjf.village.common.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 数据范围枚举 —— 数据级权限的核心。
 *
 * <p>挂在角色上(见 sys_role.data_scope),登录时汇总到 LoginUser。
 *
 * <p><b>多角色如何合并:</b>取"最宽松"的那个,即数值最小者。
 * 例如某用户同时是 SUPER_ADMIN(1 全部)和 VILLAGER(4 仅本人),
 * 他应按 1 处理 —— 否则加了个普通角色反而把管理员的权限压掉了,这不符合直觉。
 */
@Getter
public enum DataScopeEnum {

    ALL(1, "全部数据"),
    TOWN(2, "本镇数据"),
    VILLAGE(3, "本村数据"),
    SELF(4, "仅本人数据");

    private final Integer code;
    private final String description;

    DataScopeEnum(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public static DataScopeEnum of(Integer code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElse(SELF);
    }

    /**
     * 合并多个角色的数据范围,取最宽松者。
     */
    public static Integer merge(java.util.Collection<Integer> scopes) {
        return scopes.stream()
                .filter(java.util.Objects::nonNull)
                .min(Integer::compareTo)
                // 一个角色都没有时按最严格处理,不给任何越权机会
                .orElse(SELF.code);
    }
}
