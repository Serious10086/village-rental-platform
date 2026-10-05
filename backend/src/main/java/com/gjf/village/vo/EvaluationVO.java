package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 评价展示对象。
 *
 * <p><b>匿名评价的脱敏在服务端做,不是在服务端"不查"。</b>
 * 数据库里始终存着真实的 from_user_id —— 纠纷处理时平台需要能追溯到人;
 * 但返回给前端时,匿名的评价人姓名和头像会被替换成"匿名用户"。
 *
 * <p>反过来做(匿名就不查用户信息)会更省事,但一旦将来要做
 * "争议评价核实",数据链就断了。<b>保留事实、控制展示</b>是更稳的分工。
 */
@Data
public class EvaluationVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long orderId;
    private Long resourceId;

    private Long fromUserId;
    private Long toUserId;

    private Integer score;
    private Integer resourceScore;
    private Integer serviceScore;

    private String content;
    private List<String> images;
    private Integer isAnonymous;

    private String replyContent;
    private LocalDateTime replyTime;

    private Integer status;
    private LocalDateTime createTime;

    // ── 联出的展示信息 ──────────────────────────────────────────

    private String orderNo;
    private String resourceName;
    private String resourceCover;

    /** 评价人姓名(匿名时被替换为"匿名用户") */
    private String fromUserName;
    private String fromUserAvatar;

    /** 被评价人姓名 —— 被评价人自己看自己收到的评价时,这个字段才有值 */
    private String toUserName;

    /**
     * 当前用户能否回复这条评价。
     *
     * <p>只有被评价人本人、且尚未回复过,才为 true。
     * 由服务端算好下发,前端不用自己判断"我是不是被评价人"。
     */
    private Boolean canReply;
}
