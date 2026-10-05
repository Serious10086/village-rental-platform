package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 菜单权限表(菜单与按钮共用一张表,靠 menuType 区分)。
 *
 * <ul>
 *   <li>M = 目录(只有分组作用,不对应页面)</li>
 *   <li>C = 菜单(对应一个前端页面)</li>
 *   <li>F = 按钮(页面内的操作权限,通过 perms 与后端 @RequiresPerm 对应)</li>
 * </ul>
 */
@Data
@TableName("sys_menu")
public class SysMenu implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long parentId;
    private String menuName;

    /** 类型:M目录 C菜单 F按钮 */
    private String menuType;

    /** 权限标识,如 resource:audit */
    private String perms;

    /** 前端路由路径 */
    private String path;

    /** 前端组件路径 */
    private String component;

    private String icon;
    private Integer sortOrder;

    /** 是否显示:1显示 0隐藏 */
    private Integer visible;

    /** 状态:1启用 0停用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 子节点,构建菜单树用,不落库 */
    @TableField(exist = false)
    private List<SysMenu> children;
}
