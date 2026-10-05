package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一次问答的结果 —— 智能客服对话框拿到的就是这个。
 *
 * <p><b>降级原因要返回给前端。</b>用户看到的答案和 AI 答的长得不一样
 * (兜底话术是固定段落),如果不告诉他"这次是兜底",他会以为客服就这个水平。
 * 前端据此在气泡下面挂一个「本次为智能客服兜底话术」的小标记 ——
 * 说破比瞒着好,这也是这个项目里反复出现的一条。
 */
@Data
public class AiAnswerVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 本轮记录的 id,前端可以拿它标记"已解决" */
    private Long recordId;

    private String sessionId;

    private String question;

    private String answer;

    /** 1成功 2降级 3失败。前端按它决定要不要显示兜底标记 */
    private Integer status;
    /** 状态中文名 */
    private String statusName;

    /** 降级/失败的原因;成功时为 null */
    private String degradeReason;

    private String model;

    private Integer promptTokens;
    private Integer completionTokens;

    /** 耗时(毫秒) */
    private Long costTime;

    private LocalDateTime createTime;
}
