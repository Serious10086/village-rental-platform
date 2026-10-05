package com.gjf.village.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 我的钱包概览。
 *
 * <p>四个数字的<b>口径</b>必须说清楚,否则用户一定会问
 * "为什么可提现余额和累计收益对不上":
 *
 * <table border="1">
 *   <tr><th>字段</th><th>口径</th></tr>
 *   <tr><td>{@code balance}</td>
 *       <td>{@code sys_user.balance} 的当前值 —— 已经扣掉待审核和已打款的提现</td></tr>
 *   <tr><td>{@code totalIncome}</td>
 *       <td>累计入账:卖家结算 + 押金退还,只算成功的流水。<b>只增不减</b></td></tr>
 *   <tr><td>{@code totalWithdraw}</td>
 *       <td>累计提现:待审核 + 已打款。已驳回的不算 —— 钱没出去过</td></tr>
 *   <tr><td>{@code pendingWithdraw}</td>
 *       <td>其中还在待审核的那部分,给用户一个"钱在路上"的交代</td></tr>
 * </table>
 *
 * <p>不做 {@code balance = totalIncome - totalWithdraw} 的恒等式,是因为
 * 这个系统还有买家付款这条出账路径。真正的恒等式是
 * "累计入账 − 累计付款 − 累计提现 = 余额",四个数字合起来才是完整的对账,
 * 所以宁可分开摆着,也不硬凑一个看起来简洁但会被问倒的公式。
 */
@Data
public class WalletVO {

    /** 可提现余额 */
    private BigDecimal balance;

    /** 累计收益(卖家结算 + 押金退还) */
    private BigDecimal totalIncome;

    /** 累计提现(含待审核) */
    private BigDecimal totalWithdraw;

    /** 待审核的提现额 */
    private BigDecimal pendingWithdraw;

    /** 提现起提门槛,由后端下发,前端不要自己写死 10 */
    private BigDecimal minWithdrawAmount;
}
