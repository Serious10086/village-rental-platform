package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 角色表。
 *
 * <p>数据范围(data_scope)挂在角色上而非用户上 —— 因为"村级管理员"这个角色的
 * 定义本身就包含"只能看本村",它是角色属性而不是个人属性。
 */
@Data
@TableName("sys_role")
public class SysRole implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String roleCode;
    private String roleName;

    /** 数据范围:1全部 2本镇 3本村 4仅本人 */
    private Integer dataScope;

    private Integer sortOrder;
    private Integer status;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
