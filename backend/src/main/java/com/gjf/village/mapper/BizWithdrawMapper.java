package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gjf.village.dto.WithdrawQueryDTO;
import com.gjf.village.entity.BizWithdraw;
import com.gjf.village.vo.WithdrawVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * 提现单 Mapper。
 *
 * <p><b>这里没有"给余额加钱"的方法,这是有意的。</b>
 * 驳回提现要退回资金,那个动作走 {@code SysUserMapper.increaseBalance}
 * —— 全系统只有那一个地方对余额做加法。如果这里再开一个入口,
 * 以后想加"余额变动都要有流水"这类约束时就要改两处。
 */
@Mapper
public interface BizWithdrawMapper extends BaseMapper<BizWithdraw> {

    /**
     * 提现单分页。
     *
     * <p>列表联出申请人、审核人、村组 —— 后台审核时,"谁提的、哪个村的"
     * 是决定要不要放行的第一判断依据,点进去才看到太慢。
     */
    IPage<WithdrawVO> selectWithdrawPage(IPage<WithdrawVO> page,
                                         @Param("q") WithdrawQueryDTO query);

    /**
     * 按状态汇总提现额。
     *
     * <p>传 {@code [0, 1]} 得到"累计提现"(待审核 + 已打款),
     * 传 {@code [0]} 得到"在途金额"。<b>已驳回的永远不参与汇总</b> ——
     * 那笔钱从没离开过账户,算进去会让用户觉得钱少了。
     */
    BigDecimal sumAmountByStatus(@Param("userId") Long userId,
                                 @Param("statuses") List<Integer> statuses);
}
