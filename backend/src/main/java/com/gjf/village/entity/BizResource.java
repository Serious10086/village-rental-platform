package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 闲置资源表。
 *
 * <p>本表是全系统的核心 —— 订单、评价、信用、行为记录全都挂在它上面。
 */
@Data
@TableName("biz_resource")
public class BizResource implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 资源编号,如 R20260917000001 */
    private String resourceNo;

    /** 发布村民ID */
    private Long publisherId;

    /** 所在村组ID —— 数据级权限锚点 */
    private Long villageId;

    private Long categoryId;

    private String resourceName;
    private String coverUrl;

    /** 多图 URL,逗号分隔 */
    private String images;

    /** 资源标签,逗号分隔。既参与全文检索,也作为推荐的一路信号 */
    private String tags;

    private String description;

    /** 交易方式:1出租 2出售 */
    private Integer tradeType;

    /** 价格:日租金(出租)或售价(出售) */
    private BigDecimal price;

    /** 计价单位:1元/天 2元/次 3元/件(出租用) */
    private Integer priceUnit;

    /** 押金(出租用) */
    private BigDecimal deposit;

    /** 可租起始日 */
    private LocalDate rentStartDate;

    /** 可租截止日 */
    private LocalDate rentEndDate;

    /** 可租星期,如 "1,2,3,4,5"(1=周一) */
    private String rentableWeekdays;

    /** 使用规则(出租用) */
    private String useRule;

    /** 新旧程度:1全新 2九成新 3七成新 4五成新及以下(出售用) */
    private Integer oldDegree;

    /** 交货方式:1自提 2协商(出售用) */
    private Integer deliveryType;

    private String location;
    private BigDecimal longitude;
    private BigDecimal latitude;

    /** 风险等级,继承自分类 */
    private Integer riskLevel;

    /** 是否需培训确认 */
    private Integer needTraining;

    /** 安全使用须知 */
    private String trainingContent;

    /** 状态:0待审核 1已上架 2临时下架 3交易中 4已完成 5审核驳回 6违规下架 */
    private Integer resourceStatus;

    private String offlineReason;
    private Long auditUserId;
    private LocalDateTime auditTime;

    private Integer viewCount;
    private Integer favoriteCount;
    private Integer orderCount;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
