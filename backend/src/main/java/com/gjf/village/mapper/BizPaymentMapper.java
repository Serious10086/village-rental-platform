package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gjf.village.dto.PaymentQueryDTO;
import com.gjf.village.entity.BizPayment;
import com.gjf.village.vo.PaymentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

/**
 * 支付流水 Mapper。
 *
 * <p>流水是只增不改的账目,所以没有自定义的写操作,只有两类读:
 * 分页(钱包的"收益明细")和汇总(钱包上的累计收益)。
 */
@Mapper
public interface BizPaymentMapper extends BaseMapper<BizPayment> {

    /**
     * 我的资金流水分页。
     *
     * <p>联出订单号和资源名,是为了让"这笔钱是什么交易产生的"一眼可见 ——
     * 只显示一串流水号和金额的话,用户对不上账,客服也没法排查。
     */
    IPage<PaymentVO> selectPaymentPage(IPage<PaymentVO> page,
                                       @Param("q") PaymentQueryDTO query);

    /**
     * 累计入账(卖家结算 + 押金退还,且支付成功)。
     *
     * <p>口径写在 SQL 里而不是让调用方传 payType 列表:这个数字的含义
     * 是"平台一共给了我多少钱",不该有第二种解释。调用方能自己指定
     * 类型组合的话,同一个字段在不同页面上会算出不同的值。
     */
    BigDecimal sumIncome(@Param("userId") Long userId);
}
