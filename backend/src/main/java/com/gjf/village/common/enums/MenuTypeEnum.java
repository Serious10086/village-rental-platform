package com.gjf.village.common.enums;

import lombok.Getter;

/**
 * 菜单类型 —— 菜单与按钮共用 sys_menu 一张表,靠它区分。
 */
@Getter
public enum MenuTypeEnum {

    /** 目录:只做分组,不对应前端页面 */
    DIRECTORY("M", "目录"),

    /** 菜单:对应一个前端路由页面 */
    MENU("C", "菜单"),

    /** 按钮:页面内的操作权限,通过 perms 与后端 @RequiresPerm 对应 */
    BUTTON("F", "按钮");

    private final String code;
    private final String description;

    MenuTypeEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
