package com.gjf.village.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 下单请求。
 *
 * <p><b>刻意不接受这些字段:</b>单价、总金额、押金、卖家ID、村组ID。
 * 它们全部由服务端按资源当前数据算出来。
 *
 * <p>这是资金类接口的基本纪律:凡是能换算成钱的字段,
 * 只要允许前端传入,就等于把定价权交给了浏览器 ——
 * 改一下请求体里的 totalAmount 就能一块钱租走拖拉机。
 * 金额永远在服务端算。
 */
@Data
public class OrderCreateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "请选择要租用/购买的资源")
    private Long resourceId;

    /** 租期开始日。购买类资源不需要填 */
    private LocalDate rentStartDate;

    /** 租期结束日。购买类资源不需要填 */
    private LocalDate rentEndDate;

    /** 买家留言 */
    private String buyerRemark;

    /**
     * 高风险资源的安全培训确认。
     *
     * <p>分类的风险等级为"高"时,资源会自动带出"需培训"标记,
     * 买家必须在这里显式确认已了解安全须知才能下单。
     * 这是把"高风险资源需培训"这条需求落成一个可校验的布尔值。
     */
    private Boolean trainingConfirmed;

    /** 交货方式(购买类):1自提 2协商。不填则继承资源的设置 */
    private Integer deliveryType;
}
