package com.gjf.village.service;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.dto.PaymentQueryDTO;
import com.gjf.village.dto.WithdrawApplyDTO;
import com.gjf.village.dto.WithdrawAuditDTO;
import com.gjf.village.dto.WithdrawQueryDTO;
import com.gjf.village.vo.PaymentVO;
import com.gjf.village.vo.WalletVO;
import com.gjf.village.vo.WithdrawVO;

/**
 * 钱包服务 —— 收益明细与提现。
 *
 * <p>这个模块只有五个方法,但其中一个({@link #applyWithdraw})是整个系统里
 * <b>并发正确性最脆弱</b>的地方。见该方法的说明。
 */
public interface WalletService {

    /** 我的钱包概览(余额、累计收益、提现情况) */
    WalletVO getMyWallet();

    /** 我的资金流水分页("收益明细") */
    PageResult<PaymentVO> pageMyEarnings(PaymentQueryDTO query);

    /**
     * 申请提现。
     *
     * <p>扣款在<b>申请时</b>完成,驳回时退回。这个时机选择是整个模块
     * 最重要的设计决定,理由见实现类。
     *
     * @return 提现单ID
     */
    Long applyWithdraw(WithdrawApplyDTO dto);

    /** 我的提现记录 */
    PageResult<WithdrawVO> pageMyWithdraws(WithdrawQueryDTO query);

    // ── 后台 ────────────────────────────────────────────────────

    /** 后台提现列表(受数据级权限约束,待审核的排在前面) */
    PageResult<WithdrawVO> pageAdminWithdraws(WithdrawQueryDTO query);

    /**
     * 审核提现。
     *
     * <p>通过 → 置为已打款;驳回 → 置为已驳回<b>并把钱退回余额</b>。
     * 两种情况都会发站内信告知用户,驳回时审核意见必填。
     */
    void audit(Long id, WithdrawAuditDTO dto);
}
