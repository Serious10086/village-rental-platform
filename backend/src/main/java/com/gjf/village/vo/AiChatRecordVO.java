package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 对话记录(后台展示用)。
 *
 * <p>在实体字段之上补三样展示需要的东西:提问人、村组名、状态的中文名。
 * 中文名在服务端给,不让前端自己映射 —— 同一套状态码在两个地方各写一遍,
 * 改一处忘一处就会出现"后台写着成功、前台写着降级"。
 */
@Data
public class AiChatRecordVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String sessionId;

    private Long userId;
    private String userName;

    private Long villageId;
    private String villageName;

    private String question;

    private String answer;

    private String model;

    private Integer promptTokens;
    private Integer completionTokens;

    private Long costTime;

    private Integer status;
    /** 状态中文名:成功 / 降级 / 失败 */
    private String statusName;

    private String degradeReason;

    private Integer isResolved;
    private Integer isTransfer;

    private LocalDateTime createTime;
}
