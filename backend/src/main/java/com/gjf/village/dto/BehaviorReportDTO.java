package com.gjf.village.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 前端上报一次浏览。
 *
 * <h3>为什么浏览由前端上报,而不是后端在详情接口里顺手记</h3>
 *
 * <p>后端记当然更可靠(用户不发请求就没有那次浏览),但它拿不到两样东西:
 * <b>停留时长</b>和<b>来源</b>。停留时长是判断"这次浏览算不算认真看"的唯一依据
 * ({@code ai_user_behavior.stay_seconds}),而来源(列表页 / 搜索 / 推荐位)
 * 决定了后台能不能算出推荐位的点击率 —— 这两件事都只有前端知道。
 *
 * <p>代价是:前端不报就没有记录。所以上报的时机选在
 * "离开详情页"和"标签页切走"两处,并且用 {@code keepalive} 发,
 * 让关标签页那一下也尽量能发出去。真要严谨到不丢,得上埋点服务,
 * 那是另一个量级的东西。
 *
 * <h3>这里的字段都不可信</h3>
 *
 * <p>它们是前端传的,可以伪造。{@code source} 和 {@code staySeconds}
 * 都做了范围校验与夹取 —— 不在于"防住有心人"(他能刷点击率,
 * 但刷不出什么实际收益),而在于<b>别让一个脏值把统计数据搞成没法解释的样子</b>:
 * 一条 stay_seconds = 999999 的记录会让"最近看了 3 小时"出现在界面上。
 */
@Data
public class BehaviorReportDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "缺少资源标识")
    private Long resourceId;

    /**
     * 来源:1列表页 2详情页 3搜索 4推荐位。
     *
     * <p>不传按 2(详情页,也就是"直接进来的")处理 —— 直接打开链接或刷新页面时
     * 就是这个情况。这几个值的含义和 {@code AiUserBehavior.SOURCE_*} 一一对应。
     */
    @Min(value = 1, message = "来源取值不合法")
    @Max(value = 4, message = "来源取值不合法")
    private Integer source;

    /** 停留时长(秒)。前端自己算,服务端会夹到 [0, 7200] */
    @Min(value = 0, message = "停留时长不能为负")
    private Integer staySeconds;
}
