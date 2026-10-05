package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 某一种行为的采集量。
 *
 * <p><b>它的用处是让"推荐的数据从哪来"这件事看得见。</b>
 * 推荐位点击率回答的是"推得准不准",而这个回答的是
 * "我们到底知不知道用户干了什么" —— 两个都缺一不可:
 * 一个采集量长期是 0 的行为类型,意味着那条链路的埋点断了,
 * 而推荐会因此悄悄变笨,界面上完全看不出来。
 *
 * <p>顺带一个好处:它是这一批唯一能从 HTTP 层验证"行为有没有落库"的地方。
 * 那几张表没有对外接口(也不该有 —— 用户不需要看自己的行为日志),
 * 所以没有这个统计的话,"下单时记了一条行为"这件事就只能靠读代码相信。
 */
@Data
public class BehaviorCountVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 行为类型码 */
    private Integer type;

    /** 行为类型中文名:浏览 / 搜索 / 收藏 / 下单 / 评价 / 推荐位曝光 */
    private String name;

    /** 采集条数 */
    private Long count;
}
