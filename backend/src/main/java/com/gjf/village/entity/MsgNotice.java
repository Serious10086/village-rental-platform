package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统通知表。
 *
 * <p>所有需要"告知用户某件事发生了"的场景都写这张表:
 * 审核结果、订单状态变更、到期提醒、押金退还、信用变动……
 */
@Data
@TableName("msg_notice")
public class MsgNotice implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ── 通知类型 ────────────────────────────────────────────
    public static final int TYPE_REALNAME     = 1;  // 实名审核结果
    public static final int TYPE_RESOURCE     = 2;  // 资源审核结果
    public static final int TYPE_ORDER        = 3;  // 订单状态
    public static final int TYPE_RENT_EXPIRE  = 4;  // 到期提醒
    public static final int TYPE_DEPOSIT      = 5;  // 押金退还
    public static final int TYPE_ANNOUNCEMENT = 6;  // 系统公告
    public static final int TYPE_DISPUTE      = 7;  // 纠纷处理
    public static final int TYPE_CREDIT       = 8;  // 信用变动
    public static final int TYPE_WITHDRAW     = 9;  // 提现
    // 10 是批次 6c 加的:留言板里有人回复了你。
    // 点赞刻意<b>不</b>发通知 —— 弱互动推消息会把通知栏淹掉
    public static final int TYPE_BOARD        = 10; // 留言互动
    public static final int TYPE_OUTSIDE      = 11; // 外来用户备案结果

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 接收人 */
    private Long userId;

    /** 通知类型 */
    private Integer noticeType;

    private String title;
    private String content;

    /**
     * 业务类型与业务ID。
     *
     * <p>用于"点击通知跳转到对应页面"。比如资源审核通过的通知,
     * 点进去应该直达那个资源的详情页,而不是让用户自己去翻。
     */
    private Integer bizType;
    private Long bizId;

    /** 是否已读:1是 0否 */
    private Integer isRead;

    private LocalDateTime readTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
