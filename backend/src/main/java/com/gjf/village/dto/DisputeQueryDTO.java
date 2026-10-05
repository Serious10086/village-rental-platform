package com.gjf.village.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 纠纷查询条件。
 *
 * <p>{@code applicantId} / {@code respondentId} 在前后台的含义不同,
 * 和 {@link WithdrawQueryDTO#getUserId()} 是同一类问题:
 * <ul>
 *   <li><b>前台</b>("我的纠纷"):Service 把它固定成
 *       {@code applicantId = 我 OR respondentId = 我} 这个组合条件,
 *       并清空数据范围 —— 我要能看到自己被别人告了的纠纷</li>
 *   <li><b>后台</b>:作为筛选条件,并叠加 {@code villageIds}</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DisputeQueryDTO extends BaseQueryDTO {

    private String disputeNo;

    /** 按订单筛选 —— 订单详情页据此显示"这笔订单有没有纠纷" */
    private Long orderId;

    /** 1损坏赔偿 2押金/退款争议 3实物不符 4逾期未还 5其他 */
    private Integer disputeType;

    /** 0待受理 1处理中 2已调解 3已关闭 */
    private Integer status;

    private Long applicantId;

    private Long respondentId;

    /**
     * "与我有关"的用户ID —— 条件为
     * {@code applicant_id = ? OR respondent_id = ?}。
     *
     * <p>这个 OR 用上面两个等值字段表达不了:写成
     * {@code applicantId = 我 AND respondentId = 我} 只会查出
     * "自己告自己"这种不存在的记录,被他人投诉的纠纷一条都看不到。
     *
     * <p>不做成"relatedToMe 布尔 + 另一个ID字段"两个字段,是因为
     * 那样两个字段必须同步设置,漏设一个就会退化成"查全部"或"查不到"。
     * 收成一个可空字段,{@code != null} 本身就是开关,不存在不一致的中间态。
     */
    private Long relatedUserId;
}
