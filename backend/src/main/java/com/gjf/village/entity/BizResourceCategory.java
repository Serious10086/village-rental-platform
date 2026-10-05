package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 资源分类表(两级:大类 → 具体品类)。
 *
 * <p>risk_level 挂在分类上而不是逐个资源设置 ——
 * 发布时自动继承到资源上,这样"高风险资源需培训"由分类驱动,
 * 不需要发布者自己判断(他也判断不准)。
 */
@Data
@TableName("biz_resource_category")
public class BizResourceCategory implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long parentId;
    private String categoryName;
    private String categoryCode;
    private String icon;

    /** 默认风险等级:1低 2中 3高 */
    private Integer riskLevel;

    private Integer sortOrder;
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 子分类,构建树用,不落库 */
    @TableField(exist = false)
    private List<BizResourceCategory> children;
}
