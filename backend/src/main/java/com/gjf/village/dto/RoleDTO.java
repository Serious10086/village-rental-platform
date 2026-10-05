package com.gjf.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 角色新增 / 修改。
 */
@Data
public class RoleDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 修改时传入 */
    private Long id;

    @NotBlank(message = "请输入角色编码")
    @Size(max = 50, message = "角色编码不能超过 50 个字符")
    private String roleCode;

    @NotBlank(message = "请输入角色名称")
    @Size(max = 50, message = "角色名称不能超过 50 个字符")
    private String roleName;

    /** 数据范围:1全部 2本镇 3本村 4仅本人 */
    private Integer dataScope;

    private Integer sortOrder;

    /** 状态:1启用 0停用 */
    private Integer status;

    private String remark;
}
