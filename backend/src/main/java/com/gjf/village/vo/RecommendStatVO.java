package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 推荐位点击率。
 *
 * <h3>为什么这个页面非要有"曝光"这一列</h3>
 *
 * <p>只有点击数的话,那个数字什么也说明不了 —— 一天 50 次点击是多还是少?
 * 没有分母就答不上来。有了曝光数,点击率才有意义,
 * 也才能回答"推荐位到底有没有人点"这个真问题。
 *
 * <h3>点击率低不一定是坏事,这一点要说清楚</h3>
 *
 * <p>推荐位点击率百分之几是正常的(用户在首页本来就在挑,不一定要点推荐)。
 * 真正要看的是**趋势**:某天开始明显下降,说明推荐的内容不对了;
 * 改过算法之后上升,说明改对了。单看一个绝对值容易得出错误结论,
 * 所以下面配了按天的明细。
 *
 * <h3>做不到的那件事,也写在这里</h3>
 *
 * <p><b>分不出"哪个策略的点击率更高"。</b>要按策略拆,就得在点击记录里
 * 存下"这条推荐是用哪个策略推出来的",而 {@code ai_user_behavior}
 * 没有这一列(点击是一条浏览记录,它只知道资源 id)。
 * 加一列就能做到,但这一批没加 —— 列在待优化里。
 * 与其含糊地报一个拆不出来的数,不如把这件事说清楚。
 */
@Data
public class RecommendStatVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 推荐位曝光次数(一次推荐请求算一次) */
    private long impressions;

    /** 从推荐位点进资源详情的次数 */
    private long clicks;

    /** 点击率(%) = 点击 ÷ 曝光 */
    private BigDecimal ctr;

    /** 同期全部浏览数,用来对比"推荐位占了多大分量" */
    private long totalViews;

    /** 推荐位点击占全部浏览的比例(%) */
    private BigDecimal clickShare;

    /** 按天明细。趋势比单点值有用得多 */
    private List<RecommendDailyVO> daily;

    /**
     * 各类行为的采集量。
     *
     * <p>它回答的不是"推得准不准",而是"我们到底知不知道用户干了什么" ——
     * 某一种行为长期为 0,说明那条链路的采集断了,而推荐会因此悄悄变笨,
     * 界面上完全看不出来。所以它和点击率并排放在一起,而不是藏在别处。
     */
    private List<BehaviorCountVO> behaviors;
}
