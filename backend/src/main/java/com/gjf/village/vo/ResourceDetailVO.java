package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 资源详情。
 *
 * <p>比列表多了描述、使用规则、可租星期、经纬度等只有详情页才用得到的字段。
 * 列表页刻意不返回这些(尤其 description 是 TEXT 大字段),
 * 避免为了展示一屏卡片而把几十 KB 的文本全查出来。
 */
@Data
public class ResourceDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ── 资源本身 ────────────────────────────────────────────
    private Long id;
    private String resourceNo;
    private String resourceName;
    private String coverUrl;

    /** 多图 URL。数据库存逗号分隔字符串,由 TypeHandler 转成数组 */
    private List<String> images;

    /** 标签。同样是逗号分隔存储,对外返回数组 */
    private List<String> tags;

    private String description;

    private Integer tradeType;
    private BigDecimal price;
    private Integer priceUnit;
    private BigDecimal deposit;

    private LocalDate rentStartDate;
    private LocalDate rentEndDate;
    private List<Integer> rentableWeekdays;
    private String useRule;

    private Integer oldDegree;
    private Integer deliveryType;

    private String location;
    private BigDecimal longitude;
    private BigDecimal latitude;

    private Integer riskLevel;
    private Integer needTraining;
    private String trainingContent;

    private Integer resourceStatus;
    private String offlineReason;

    private Integer viewCount;
    private Integer favoriteCount;
    private Integer orderCount;
    private LocalDateTime createTime;

    // ── 分类 ────────────────────────────────────────────────
    private Long categoryId;
    private String categoryName;

    // ── 村组 ────────────────────────────────────────────────
    private Long villageId;
    private String villageName;
    private String townName;

    // ── 发布人 ──────────────────────────────────────────────
    private Long publisherId;
    private String publisherName;
    private String publisherAvatar;
    private Integer publisherCredit;

    /**
     * 发布人已完成订单数 —— 展示在详情页"发布人"卡片上,
     * 让租客对对方的交易经验有个判断。
     */
    private Long publisherOrderCount;

    /**
     * 当前访问者是否是发布者本人。
     *
     * 前端据此决定显示"立即预约"还是"管理我的资源" ——
     * 不显示"租自己的东西"这种按钮。
     */
    private Boolean isOwner;

    /**
     * 当前访问者是否收藏了这个资源。
     *
     * <p>由服务端在详情查询之后补上(和 {@code isOwner} 一样),
     * 而不是靠前端拿"我收藏过的 id 列表"自己比对 —— 那样前端得先
     * 拉一遍收藏列表才知道该显示实心还是空心星星,详情页会多一次请求。
     *
     * <p>游客恒为 false 而不是 null:直接打开链接的人没登录,
     * 对他来说"没收藏"就是事实。返回 null 会让前端多一个判空分支。
     */
    private Boolean favorited;
}
