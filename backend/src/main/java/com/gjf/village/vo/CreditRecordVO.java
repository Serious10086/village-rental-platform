package com.gjf.village.vo;

import com.gjf.village.entity.BizCreditRecord;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 信用流水展示对象。
 *
 * <p>在流水实体上补两样东西:业务类型的中文名,和这笔变更的<b>方向</b>。
 * 方向单独给出来,是因为前端要按它决定文字颜色(加分绿、扣分红),
 * 而前端自己写 {@code changeScore > 0 ? 'green' : 'red'} 的话,
 * 将来出现"0 分变更"这种边界情况就会显示成红色。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CreditRecordVO extends BizCreditRecord {

    /** 业务类型中文名,如"订单履约" */
    private String bizTypeName;

    /** 关联业务的单号(订单号 / 评价ID),便于用户点进去看原委 */
    private String bizNo;

    /** 是否为加分项。=0 的变更视为中性 */
    public boolean isPositive() {
        return getChangeScore() != null && getChangeScore() > 0;
    }
}
