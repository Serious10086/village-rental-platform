package com.gjf.village.vo;

import com.gjf.village.entity.UserCreditSummary;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 信用概览展示对象。
 *
 * <p>在视图字段之上补信用等级。<b>等级判定放在服务端</b>而不是前端:
 * 下单门槛是 60 分,如果前端自己写一套阈值、后端用另一套,
 * 就会出现"页面显示信用良好、下单却被拒"的割裂。阈值只应有一处定义。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CreditSummaryVO extends UserCreditSummary {

    /** 等级名称:信用优秀 / 良好 / 一般 / 较差 / 受限 */
    private String level;

    /** 等级对应的标签样式:success / primary / warning / danger */
    private String levelType;

    /** 一句话说明,直接展示给用户 */
    private String levelDesc;

    /** 距离下一个等级还差多少分;已是最高等级时为 0 */
    private Integer toNextLevel;
}
