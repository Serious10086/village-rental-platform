package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gjf.village.dto.CreditQueryDTO;
import com.gjf.village.entity.BizCreditRecord;
import com.gjf.village.vo.CreditRecordVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 信用分流水 Mapper。
 */
@Mapper
public interface BizCreditRecordMapper extends BaseMapper<BizCreditRecord> {

    /**
     * 信用流水分页。
     *
     * <p>联出订单号:用户看到"-10 分"时,下一步必然想问"是哪一单扣的"。
     * 把单号直接带在列表里,比让他自己去订单列表里翻要友好得多。
     * 只有 {@code bizType = 1}(订单履约)才有单号,其余类型留空。
     */
    IPage<CreditRecordVO> selectRecordPage(IPage<CreditRecordVO> page,
                                           @Param("q") CreditQueryDTO query);
}
