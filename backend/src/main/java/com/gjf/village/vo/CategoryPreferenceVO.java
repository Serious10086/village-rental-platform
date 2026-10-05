package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 一个分类上的偏好强度。
 *
 * <p>{@code score} 是加权得分(下单权重最高、浏览最低,浏览还看停留时长),
 * {@code behaviorCount} 是**没加权的行为条数**。两个都留着是因为它们
 * 回答的是不同的问题:
 * <ul>
 *   <li>{@code score} —— "该先推哪个分类",排序用它</li>
 *   <li>{@code behaviorCount} —— "这个结论有多少条行为撑着"。
 *       一条下单、零条浏览,得分 5;五条浏览,得分 5。两者得分相同,
 *       但前者是"他真的要买",后者是"他随便看看"。
 *       推荐理由里说"你最近看过 5 条农机"比说"得分 5"诚实得多。</li>
 * </ul>
 */
@Data
public class CategoryPreferenceVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long categoryId;

    private String categoryName;

    /** 加权得分,排序依据 */
    private Long score;

    /** 原始行为条数(不加权),用来在推荐理由里说人话 */
    private Long behaviorCount;
}
