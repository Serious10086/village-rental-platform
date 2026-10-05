package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 前端路由对象 —— {@code GET /api/auth/getRouters} 的返回元素。
 *
 * <p>前端拿到这棵树后递归转换,用 {@code router.addRoute()} 逐条注册。
 *
 * <p><b>为什么必须动态注册而不是写死静态路由再靠菜单隐藏:</b>
 * 静态路由在打包时就进了 JS,用户在地址栏手敲 URL 就能进任何后台页面。
 * 动态注册则由后端决定"这个用户有哪些路由",没授权的路由压根不存在,
 * 手敲 URL 只会跳到 404。这是菜单级权限的实质,不是体验优化。
 */
@Data
public class RouterVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 路由名称,必须唯一(用于 keep-alive 和编程式导航) */
    private String name;

    /** 路由路径 */
    private String path;

    /**
     * 组件路径。
     *
     * <p>顶层目录固定为 {@code "Layout"}(前端注册的后台骨架布局);
     * 叶子菜单是相对 views 的路径,如 {@code "audit/realname/index"}。
     */
    private String component;

    /** 路由元信息 */
    private Meta meta;

    /** 子路由 */
    private List<RouterVO> children;

    /**
     * 路由元信息 —— 对应前端路由的 meta 字段。
     */
    @Data
    public static class Meta implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 菜单标题,显示在侧边栏和面包屑 */
        private String title;

        /** 图标名(Element Plus 图标组件名) */
        private String icon;

        /** 是否在侧边栏隐藏 */
        private Boolean hidden;

        /** 访问该页面所需的权限标识(用于调试,前端不做二次校验) */
        private String perms;
    }
}
