package com.gjf.village.dto;

import lombok.Data;

import java.io.Serial;

/**
 * 公告查询。前台与后台共用。
 *
 * <p>两个村组相关的字段必须一起理解,它们对应两条<b>互斥</b>的可见性口径:
 * <ul>
 *   <li>{@code includeGlobal = true} + {@code villageId} —— 前台口径:
 *       "全平台公告 + 我的村"。</li>
 *   <li>{@code villageIds} —— 后台口径:由 {@code DataScopeHelper} 注入的
 *       白名单,村管只能看本村。</li>
 * </ul>
 * 前台那条是"两个来源的并集"(因为它要带上 {@code village_id IS NULL} 的全平台公告),
 * 后台那条是"一个集合的范围",形状本来就不同,所以没法用同一个条件表达。
 */
@Data
public class AnnouncementQueryDTO extends BaseQueryDTO {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 前台:是否带上 village_id IS NULL 的全平台公告 */
    private Boolean includeGlobal;

    /** 前台:当前访问者所属村组(与 includeGlobal 配对使用) */
    private Long villageId;

    /** 类型:1村级共享倡议 2安全使用须知 3农机补贴政策 4节庆活动 5系统公告 */
    private Integer noticeType;

    /** 标题关键词 */
    private String keyword;

    /**
     * 状态:0草稿 1已发布 2已下架。
     *
     * <p><b>只对后台口径生效。</b>前台那条 SQL 把 {@code status = 1} 硬编码
     * 在里面,压根不看这个字段 —— 因为"前台只能看已发布"是对所有调用方
     * 都成立的条件,那就该由 SQL 保证,而不是指望每个调用方都记得设置。
     */
    private Integer status;
}
