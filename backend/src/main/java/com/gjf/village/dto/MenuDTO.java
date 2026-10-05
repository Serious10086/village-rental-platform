package com.gjf.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 菜单新增 / 修改。
 *
 * <p>菜单与按钮共用一张表,靠 {@code menuType} 区分 ——
 * 按钮没有 path / component / icon,这几个字段按需留空即可。
 */
@Data
public class MenuDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 修改时传入 */
    private Long id;

    /** 上级菜单ID,null 表示顶级 */
    private Long parentId;

    @NotBlank(message = "请输入菜单名称")
    @Size(max = 50, message = "菜单名称不能超过 50 个字符")
    private String menuName;

    /** 类型:M目录 C菜单 F按钮 */
    @NotBlank(message = "请选择菜单类型")
    private String menuType;

    /** 权限标识,如 resource:audit。后端 @RequiresPerm 按它校验 */
    private String perms;

    /** 前端路由路径。目录和菜单必填,按钮不填 */
    private String path;

    /** 前端组件路径,如 admin/system/role/index */
    private String component;

    private String icon;

    private Integer sortOrder;

    /** 是否显示:1显示 0隐藏 */
    private Integer visible;

    /** 状态:1启用 0停用 */
    private Integer status;
}
