package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 一个村的资源与交易统计 —— <b>与视图 {@code v_village_resource_stat} 一一对应</b>。
 *
 * <p>字段名和视图列名严格对齐(靠 MyBatis 的下划线转驼峰自动映射),不额外加工。
 * 视图是在批次 1 就设计好的读模型,它存在的意义就是"村级统计、
 * 超管各村对比、后台公示页三个页面共用一套口径" ——
 * <b>在 Java 侧再算一遍,等于把那份口径复制成两份。</b>
 */
@Data
public class VillageStatVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long villageId;
    private String villageName;
    private String townName;

    private Long resourceTotal;
    private Long onlineCount;
    private Long rentCount;
    private Long sellCount;
    private Long highRiskCount;

    private Long userTotal;
    private Long orderTotal;
    private Long orderFinished;
    private Long orderUsing;

    /** 租售利用率(%)=(交易中 + 已完成) ÷ 资源总数。直接对应需求原文 |
     *  视图里已经用 IFNULL 兜过底,没有资源时是 0 而不是 null */
    private BigDecimal usageRate;
}
