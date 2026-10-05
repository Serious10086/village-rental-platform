package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 推荐位里的一张卡片。
 *
 * <h3>{@code reason} 这个字段是刻意留的</h3>
 *
 * <p>一条"猜你喜欢"如果只是甩出一排资源,用户不知道它凭什么出现在那儿;
 * 而带上理由("你看过农机具""本村这周最热"),推荐就从<b>黑箱</b>变成了
 * <b>可解释</b>的东西 —— 推荐得不对时用户知道为什么不对,
 * 推荐得对时他会更愿意点。
 *
 * <p>对论文来说它还有第二个用处:答辩被问"你的推荐是怎么算出来的"时,
 * 指着页面上那句理由讲就够了,不用现场回忆公式。
 *
 * <h3>{@code strategy} 和 {@code heatScore}</h3>
 *
 * <p>前端一般不展示它们,留着是为了两件事:推荐位点击率要<b>按策略分组</b>
 * 统计(内容推荐和本村热门哪个更受欢迎,是论文里一张很自然的对比表),
 * 以及出问题时要能看出"这条是怎么混进来的"。
 */
@Data
public class RecommendItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ── 推荐策略 ────────────────────────────────────────────────
    /** 基于内容:来自用户的分类偏好 */
    public static final int STRATEGY_CONTENT = 1;
    /** 村组热度:本村最热的几个 */
    public static final int STRATEGY_VILLAGE_HOT = 2;
    /** 全局兜底:冷启动或者前面两条凑不够数时用 */
    public static final int STRATEGY_FALLBACK = 3;

    // ── 资源卡片字段 ────────────────────────────────────────────
    private Long id;
    private String resourceNo;
    private String resourceName;
    private String coverUrl;
    private Integer tradeType;
    private BigDecimal price;
    private Integer priceUnit;

    private Long categoryId;
    private String categoryName;
    private String villageName;

    /** 发布人信息。卡片要展示,口径与 v_resource_list 保持一致 */
    private String publisherName;
    private String publisherAvatar;
    private Integer publisherCredit;

    private Integer viewCount;
    private Integer favoriteCount;
    private Integer orderCount;

    /** 推荐理由,直接展示给用户的一句话 */
    private String reason;

    /** 见上面的 STRATEGY_* */
    private Integer strategy;

    /** 综合热度分,排序依据。前端不展示,排查和论文用 */
    private Long heatScore;
}
