package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 村组表。
 *
 * <p>它是全系统数据级权限的锚点 —— 村级管理员的查询条件最终都落到 village_id 上。
 */
@Data
@TableName("sys_village")
public class SysVillage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 上级行政区ID,NULL 表示顶级(乡镇) */
    private Long parentId;

    private String villageName;
    private String villageCode;
    private String townName;
    private String regionCode;
    private Integer sortOrder;

    /** 状态:1启用 0停用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 子节点,仅用于构建树形结构时不落库 */
    @TableField(exist = false)
    private java.util.List<SysVillage> children;
}
