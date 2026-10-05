package com.gjf.village.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 信用流水 / 信用列表查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CreditQueryDTO extends BaseQueryDTO {

    /** 用户ID —— "某个人的信用流水"由 Service 注入,后台列表则可为空(查全村) */
    private Long userId;

    /** 业务类型:1订单履约 2资源完好 3用户评价 4管理员手动 5违规处罚 */
    private Integer bizType;

    /** 关键词:按姓名或手机号搜用户(后台信用列表用) */
    private String keyword;

    /** 信用分上限,用于筛"低信用用户" */
    private Integer maxScore;

    /** 信用分下限 */
    private Integer minScore;
}
