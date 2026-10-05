package com.gjf.village.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 提现申请。
 *
 * <p><b>金额的下限不在这里写死。</b>起提门槛是 10 元,它同时出现在
 * 校验逻辑和 {@link com.gjf.village.vo.WalletVO} 下发给前端的展示值里,
 * 好处是调门槛只用改一处。{@code @DecimalMin} 这里只拦住
 * 非正数这种无论如何都不合法的情况。
 *
 * <p>{@code @Digits(integer = 8, fraction = 2)} 对应
 * {@code DECIMAL(10,2)}:整数位最多 8 位、小数最多 2 位。
 * 不校验的话,用户填 0.005 会被 MySQL 静默四舍五入成 0.01,
 * 或者填 99999999999 直接报数据库错误 —— 两种都不该让用户碰到。
 */
@Data
public class WithdrawApplyDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "请填写提现金额")
    @DecimalMin(value = "0.01", message = "提现金额必须大于 0")
    @Digits(integer = 8, fraction = 2, message = "金额最多两位小数")
    private BigDecimal amount;

    /** 收款方式:1微信 2支付宝 3银行卡 */
    @NotNull(message = "请选择收款方式")
    private Integer accountType;

    @NotBlank(message = "请填写收款账号")
    @Size(max = 64, message = "收款账号过长")
    private String accountNo;

    /** 收款人姓名,选填。银行卡场景下跟实名信息对不上会被人工打回 */
    @Size(max = 50, message = "收款人姓名过长")
    private String accountName;
}
