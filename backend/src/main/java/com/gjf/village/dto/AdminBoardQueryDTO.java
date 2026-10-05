package com.gjf.village.dto;

import lombok.Data;

import java.io.Serial;

/**
 * 后台留言管理查询。
 *
 * <p>与 {@link BoardQueryDTO} 的分工见那个类的注释:
 * 这里的村组过滤<b>只有一个来源</b> —— {@code DataScopeHelper} 注入的
 * {@code villageIds} 白名单。所以本类刻意不提供 {@code villageId} 字段,
 * 让"前端传村组 id 越权看别村留言"这件事在类型层面就做不到。
 */
@Data
public class AdminBoardQueryDTO extends BaseQueryDTO {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 正文关键词 */
    private String keyword;

    /**
     * 只看主帖。
     *
     * <p>后台列表默认<b>主帖和回复都显示</b> —— 回复同样可能违规,
     * 只巡查主帖会漏掉一半。但排查"某条回复被删了主帖为什么还在"
     * 这类问题时,能只看主帖会方便很多。
     */
    private Boolean onlyRoot;

    /** 只看命中了敏感词的,便于巡查过滤效果 */
    private Boolean onlyFiltered;

    /** 状态:1正常 2已隐藏 */
    private Integer status;
}
