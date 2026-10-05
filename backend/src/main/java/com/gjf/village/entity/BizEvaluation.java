package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单评价表。
 *
 * <p><b>一单一评由数据库的唯一约束保证</b>,而不是靠 Service 里"先查再插":
 * {@code uk_order_from(order_id, from_user_id)} 让同一个人对同一笔订单
 * 只能留下一条评价。Service 层的"先查后插"在并发下有两个请求同时查到
 * "还没有评价"、然后双双插入的窗口,唯一约束才是真正拦得住的那一层。
 *
 * <p><b>为什么评价是双向的:</b>买家评卖家的资源和服务,卖家也评买家
 * 是否爱惜东西、是否按时归还。只做单向的话,信用分只能反映一半的事实 ——
 * 一个总是拖延归还的租户,他的信用分不会受任何影响。
 *
 * <p><b>三个评分维度的分工:</b>
 * <ul>
 *   <li>{@code score} 综合评分 —— 展示用,也是"评价均分"口径的来源</li>
 *   <li>{@code resourceScore} 资源相符度 —— "资源完好率"口径的来源。
 *       描述写的是九成新,拿到手发现是五成新,这一项就会打低分</li>
 *   <li>{@code serviceScore} 服务态度 —— 只影响展示,不参与信用计算</li>
 * </ul>
 * 后两项选填:强制打分只会逼出一堆敷衍的中间值,不如让愿意细评的人细评。
 */
@Data
@TableName("biz_evaluation")
public class BizEvaluation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态:正常 */
    public static final int STATUS_NORMAL = 1;
    /** 状态:已隐藏(违规) */
    public static final int STATUS_HIDDEN = 2;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long resourceId;

    /** 评价人ID */
    private Long fromUserId;

    /** 被评价人ID */
    private Long toUserId;

    /** 综合评分 1-5 */
    private Integer score;

    /** 资源相符度 1-5,参与"资源完好率"计算 */
    private Integer resourceScore;

    /** 服务态度 1-5 */
    private Integer serviceScore;

    private String content;

    /** 评价图片,逗号分隔 */
    private String images;

    /** 是否匿名:1是 0否 */
    private Integer isAnonymous;

    /** 被评价人的回复 —— 差评的申诉通道,没有它被评价方只能被动挨打 */
    private String replyContent;

    private LocalDateTime replyTime;

    /** 村组ID —— 数据级权限锚点 */
    private Long villageId;

    /** 状态:1正常 2隐藏(违规) */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
