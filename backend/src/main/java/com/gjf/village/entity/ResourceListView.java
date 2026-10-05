package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 资源列表视图映射(v_resource_list)。
 *
 * <p><b>这是只读对象</b> —— 永远不要对它做 insert / update / delete。
 *
 * <p>为什么不额外建一个 ResourceListVO 再转换一次:视图本身就是为"列表展示"
 * 这个场景设计的读模型,字段和前端要的完全一致。再加一层 1:1 的
 * Entity → VO 映射,除了多写一个类、多一次循环赋值,没有任何收益。
 *
 * <p><b>要注意的地方:</b>视图里的 {@code deleted = 0} 和
 * {@code resource_status = 1} 是写死在定义里的,MyBatis-Plus 的
 * {@code @TableLogic} 对视图不生效(视图里没有 deleted 列),
 * 所以这里不能加 @TableLogic —— 加了反而会拼出错误的 SQL。
 */
@Data
@TableName("v_resource_list")
public class ResourceListView implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String resourceNo;
    private String resourceName;
    private String coverUrl;
    private String images;
    private String tags;

    /** 1出租 2出售 */
    private Integer tradeType;

    private BigDecimal price;
    private Integer priceUnit;
    private BigDecimal deposit;
    private Integer oldDegree;

    private LocalDate rentStartDate;
    private LocalDate rentEndDate;
    private String rentableWeekdays;

    private String location;
    private Integer viewCount;
    private Integer orderCount;
    private Integer favoriteCount;
    private Integer riskLevel;
    private Integer needTraining;
    private LocalDateTime createTime;

    private Long publisherId;
    private Long villageId;
    private Long categoryId;

    /** 以下字段来自 JOIN,不属于 biz_resource 表 */
    private String categoryName;
    private String categoryCode;
    private String villageName;
    private String townName;
    private String publisherName;
    private String publisherAvatar;
    private Integer publisherCredit;
}
