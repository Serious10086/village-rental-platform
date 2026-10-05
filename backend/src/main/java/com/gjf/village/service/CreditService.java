package com.gjf.village.service;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.dto.CreditAdjustDTO;
import com.gjf.village.dto.CreditQueryDTO;
import com.gjf.village.vo.CreditRecordVO;
import com.gjf.village.vo.CreditSummaryVO;

/**
 * 信用服务 —— 全系统信用分变更的唯一入口。
 *
 * <p><b>为什么把加/减分收敛到一个方法里:</b>
 * 信用分的变更来源是分散的 —— 订单完成要加、评价要加、管理员要手动调、
 * 纠纷判罚要扣。如果每个业务各自去改 {@code sys_user.credit_score}、
 * 各自写一条流水,那么"分数变了但流水没写"或"流水写了但分数没变"
 * 这类不一致迟早会出现,而且没有任何一层拦得住。
 *
 * <p>收敛之后,{@code adjust()} 是唯一能碰信用分的地方:
 * 加分和写流水在同一个事务里,要么都对、要么都不对。
 */
public interface CreditService {

    /**
     * 调整信用分并写一条变更流水。
     *
     * <p><b>调用方必须已经在事务中</b> —— 这个方法本身不加 {@code @Transactional},
     * 因为它总是某个更大业务动作(完成订单、提交评价)的一部分。
     * 单独给它开事务会让"业务失败了但分已经加了"成为可能。
     *
     * @param userId    被调整的用户
     * @param delta     变更值,正加负减
     * @param reason    变更原因,会直接展示给用户看,要写成人话
     * @param bizType   业务类型,见 {@code BizCreditRecord.BIZ_*}
     * @param bizId     关联业务ID(订单ID / 评价ID),可为空
     * @param villageId 村组ID,数据级权限锚点,不能为空
     */
    void adjust(Long userId, int delta, String reason, int bizType,
                Long bizId, Long villageId);

    /** 我的信用概览(含等级判定) */
    CreditSummaryVO getMyCredit();

    /** 某个用户的信用概览 */
    CreditSummaryVO getCreditSummary(Long userId);

    /** 我的信用流水 */
    PageResult<CreditRecordVO> pageMyRecords(CreditQueryDTO query);

    // ── 后台 ────────────────────────────────────────────────────

    /** 后台信用列表(受数据级权限约束) */
    PageResult<CreditSummaryVO> pageCreditList(CreditQueryDTO query);

    /** 后台查看某个用户的信用流水 */
    PageResult<CreditRecordVO> pageUserRecords(CreditQueryDTO query);

    /** 管理员手动调整信用分 */
    void adjustByAdmin(CreditAdjustDTO dto);
}
