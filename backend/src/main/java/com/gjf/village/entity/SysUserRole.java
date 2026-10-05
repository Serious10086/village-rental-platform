package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户角色关联表(多对多)。
 *
 * <p>之所以是多对多而不是在 sys_user 上加一个 role_id 字段:
 * 村长自己也有农机要出租,他同时是 VILLAGE_ADMIN 和 VILLAGER,一个字段装不下。
 */
@Data
@TableName("sys_user_role")
public class SysUserRole implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long roleId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
