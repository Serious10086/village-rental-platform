package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 「我的收藏」列表里的一张卡片。
 *
 * <h3>为什么不复用 ResourceListView</h3>
 *
 * <p>{@code ResourceListView} 映射的是视图 {@code v_resource_list},而那个视图
 * 把 {@code resource_status = 1} 写死在定义里(它在批次 1 就是为了
 * "前台只看已上架"设计的)。拿它当收藏列表的数据源,后果是
 * <b>收藏过的资源一旦下架或售出,就会从收藏列表里静默消失</b> ——
 * 用户只会觉得"平台把我的收藏弄丢了"。
 *
 * <p>所以这里直接查 {@code biz_resource},并且把 {@code resourceStatus}
 * 带出来,让前端能标一个「已下架」而不是把整条藏掉。
 * <b>"东西没了"和"东西被系统藏了"是两件事,用户需要知道是哪一件。</b>
 *
 * <h3>为什么字段只有十几个</h3>
 *
 * <p>不做成 {@code ResourceListView} 的 1:1 拷贝:收藏卡片和前台的资源卡片
 * 展示重点不同 —— 前者要"我什么时候收的、现在还能不能租",后者要"押金多少、
 * 可租日期"。照抄 30 个字段的结果是其中一半永远没人读,而读代码的人
 * 分不清哪些是真的在用。
 */
@Data
public class FavoriteItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 资源ID */
    private Long id;
    private String resourceNo;
    private String resourceName;
    private String coverUrl;

    private Integer tradeType;
    private BigDecimal price;
    private Integer priceUnit;

    /** 资源当前状态:1已上架 2待审核 3交易中 …(前端据此标「已下架」) */
    private Integer resourceStatus;

    private Long categoryId;
    private String categoryName;
    private String villageName;
    private String publisherName;

    /** 资源的热度,摆在收藏列表里当"值不值得回头看"的参考 */
    private Integer viewCount;
    private Integer favoriteCount;

    /** 资源发布时间 */
    private LocalDateTime createTime;

    /** 我收藏它的时间 —— 收藏列表按它倒序 */
    private LocalDateTime favoriteTime;
}
