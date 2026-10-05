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
 * 公告表(政策宣传)。
 *
 * <p><b>{@code village_id} 允许为 NULL,这是本表唯一一处需要留神的设计。</b>
 * 村级管理员发布时强制写自己的 village_id;超管发全局公告时为 NULL。
 * 于是前台的可见性条件写成:
 * <pre>
 *   WHERE village_id IS NULL OR village_id = 我的村组
 * </pre>
 * 这条条件在外层拼,<b>不能固化进接口</b> —— 因为"我的村组"因角色而异。
 * 但"状态必须是已发布"对所有调用方一致,它固化在查询里。
 * 判断依据和 4 个视图那一节是同一条:条件对所有人一致就能内聚,
 * 因角色而异就必须留外层。
 *
 * <p><b>未登录用户的可见范围:</b>他们没有村组,只匹配得上
 * {@code village_id IS NULL} 的全平台公告。这正好是想要的效果 ——
 * 村内的政策宣传不该被外村人(乃至搜索引擎)看到,而平台级公告本来就是公开的。
 */
@Data
@TableName("biz_announcement")
public class BizAnnouncement implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ── 公告类型 ────────────────────────────────────────────────
    public static final int TYPE_SHARING  = 1;  // 村级共享倡议
    public static final int TYPE_SAFETY   = 2;  // 安全使用须知
    public static final int TYPE_SUBSIDY  = 3;  // 农机补贴政策
    public static final int TYPE_FESTIVAL = 4;  // 节庆活动
    public static final int TYPE_SYSTEM   = 5;  // 系统公告

    // ── 状态 ────────────────────────────────────────────────────
    public static final int STATUS_DRAFT    = 0;  // 草稿
    public static final int STATUS_PUBLISHED = 1; // 已发布
    public static final int STATUS_OFFLINE  = 2;  // 已下架

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String title;

    /** 正文(富文本) */
    private String content;

    private String coverUrl;

    /** 类型,见上面的 TYPE_* */
    private Integer noticeType;

    private Long publisherId;

    /** 村组ID。NULL = 全平台公告 */
    private Long villageId;

    /** 是否置顶:1是 0否。列表排序按它打头 */
    private Integer isTop;

    private Integer viewCount;

    /** 状态:0草稿 1已发布 2已下架 */
    private Integer status;

    private LocalDateTime publishTime;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
