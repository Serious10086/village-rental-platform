package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 推荐位点击率的某一天。
 *
 * <p>做成独立的类而不是 {@code RecommendStatVO} 的嵌套类:MyBatis 映射嵌套类
 * 要走 {@code Outer$Inner} 那种写法,不如顶层类直白。这点在批次 7 已经
 * 踩过一次(降级原因的分布),不再重复。
 */
@Data
public class RecommendDailyVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 日期,形如 2026-09-27 */
    private String date;

    private long impressions;

    private long clicks;

    /** 当天点击率(%) */
    private BigDecimal ctr;
}
