package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 订单趋势上的一个点(某一天下了几单)。
 *
 * <p>{@code date} 是字符串而不是 {@code LocalDate}:它的唯一用途是画折线图的
 * X 轴标签,前端拿到就显示。用日期类型反而要多一层序列化格式的约定。
 */
@Data
public class OrderTrendVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 形如 2026-09-28 */
    private String date;

    private Long count;
}
