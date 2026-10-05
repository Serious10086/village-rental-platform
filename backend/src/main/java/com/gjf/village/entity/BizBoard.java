package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 留言板表(主帖 + 回复共用一张表)。
 *
 * <p><b>为什么回复不另建一张表:</b>回复和主帖的字段完全同构 ——
 * 都是"谁、在哪条下面、说了什么、被赞了几次、什么时候说的"。
 * 拆成 {@code biz_board_reply} 只会让每次读主帖都要多一次 JOIN,
 * 而换不来任何约束上的好处。用 {@code parent_id} 自关联表达层级,
 * 一张表就够了。
 *
 * <p><b>为什么只允许两级(主帖 → 回复),不做到无限嵌套:</b>
 * {@code parent_id} 只指向主帖,回复不能再对回复回复。这不是能力缺失 ——
 * 无限嵌套的讨论区(像 Reddit)需要递归查询和树形渲染,
 * 而村级留言板的实际用法是"有人问、几个人答",两级足够。
 * 真要追问某一条,正确做法是回复时 @ 那个人,而不是再挂一层。
 * 把层级压到两级,列表接口就能一次查完,不需要递归 CTE。
 *
 * <p><b>与 {@code biz_evaluation} 的区别(容易被问):</b>
 * 评价挂在订单上、有评分维度、一单一评;留言板是公共讨论区,
 * 不绑定任何订单。两者都有"内容 + 敏感词过滤",但业务语义完全不同,
 * 合成一张表会出现大量只能为空的列。
 */
@Data
@TableName("biz_board")
public class BizBoard implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态:正常 */
    public static final int STATUS_NORMAL = 1;
    /** 状态:已隐藏(违规) */
    public static final int STATUS_HIDDEN = 2;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 父留言ID。
     *
     * <p>{@code NULL} = 主帖;非空 = 回复,且指向的必然是一条主帖。
     * 这个不变量由 Service 层保证(回复时先查父帖的 parent_id 是否为空)。
     */
    private Long parentId;

    /** 留言人 */
    private Long userId;

    /** 所属村组 —— 数据级权限的过滤锚点 */
    private Long villageId;

    /** 留言内容。替换类敏感词在库里存原文,读取时才打码 */
    private String content;

    /** 图片,逗号分隔 */
    private String images;

    /** 点赞数(冗余计数,与 biz_board_like 的行数必须一致) */
    private Integer likeCount;

    /** 回复数(冗余计数,只对主帖有意义;回复行上恒为 0) */
    private Integer replyCount;

    /** 是否命中敏感词:1是 0否。命中时正文存的是原文,展示时过滤 */
    private Integer isFiltered;

    /** 状态:1正常 2已隐藏 */
    private Integer status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
