package com.gjf.village.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 申请纠纷。
 *
 * <p><b>申请人、被申请人、村组全部由服务端从订单推出来</b>,不接受前端传入。
 * 允许前端传 {@code respondentId} 的话,就能构造一条"我告了一个跟这笔订单
 * 完全无关的人"的纠纷记录,而被申请人甚至不会收到通知。
 *
 * <p>{@code orderStatusBefore} 同理,由服务端在申请的那一刻读出来写进去。
 * 如果从前端传,申请书可以被篡改成"本来订单是使用中"来骗取调解倾向。
 */
@Data
public class DisputeApplyDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "缺少订单ID")
    private Long orderId;

    /** 1损坏赔偿 2押金/退款争议 3实物不符 4逾期未还 5其他 */
    @NotNull(message = "请选择纠纷类型")
    @Min(value = 1, message = "纠纷类型不合法")
    @Max(value = 5, message = "纠纷类型不合法")
    private Integer disputeType;

    @NotBlank(message = "请描述纠纷情况")
    @Size(max = 1000, message = "纠纷描述最多 1000 字")
    private String description;

    /** 证据图片 URL 列表,最多 9 张 */
    @Size(max = 9, message = "证据图片最多 9 张")
    private List<String> evidenceImages;

    /**
     * 诉求金额,选填。
     *
     * <p>不设为必填是因为有一类纠纷根本不涉及钱(比如"实物与描述不符,
     * 我要求换一台"),硬要填一个数字反而会诱导申请人随手写个价。
     */
    @Digits(integer = 8, fraction = 2, message = "金额最多两位小数")
    private BigDecimal applyAmount;
}
