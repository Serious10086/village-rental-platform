package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 一张统计图上的一个数据项(名称 + 数量)。
 *
 * <p>饼图、柱状图、排行榜要的都是这个形状,所以合成一个类,不按图类型各写一份。
 * {@code name} 是**已经翻译好中文**的:订单状态在数据库里是 0-7 的数字,
 * 直接丢给前端的话,前端就得自己维护一份"0=待卖家确认"的映射 ——
 * 而那份映射在 `OrderStatusEnum` 里已经有了,写两遍迟早不一致。
 */
@Data
public class StatItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;

    private Long count;

    public StatItemVO() {
    }

    public StatItemVO(String name, Long count) {
        this.name = name;
        this.count = count;
    }
}
