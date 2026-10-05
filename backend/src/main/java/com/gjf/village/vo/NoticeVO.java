package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 通知列表项。
 */
@Data
public class NoticeVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Integer noticeType;

    /** 类型的中文名,后端翻译好再返回 —— 前端只负责显示 */
    private String noticeTypeName;

    private String title;
    private String content;

    private Integer bizType;
    private Long bizId;

    private Integer isRead;
    private LocalDateTime readTime;
    private LocalDateTime createTime;

    /**
     * 点击后应该跳转的前端路径。
     *
     * <p>由后端算好而不是让前端拼:跳转规则跟业务强相关
     * (资源审核通知跳详情页、实名通知跳个人中心……),
     * 放在后端改一处就生效,前端不用为每种类型写一段 if。
     */
    private String linkPath;
}
