package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * AI 降级率统计。
 *
 * <p><b>降级率的分母是"全部记录",这一点要写清楚</b>,否则这个数字没法解释。
 * 三种记录的语义不同:
 * <ul>
 *   <li>{@code status = 1 成功} —— AI 正常回答了</li>
 *   <li>{@code status = 2 降级} —— 调用了但没成功,返回了本地兜底话术</li>
 *   <li>{@code status = 3 失败} —— 压根没调用(提问被内容安全拦下),或内部出错</li>
 * </ul>
 * 所以:
 * <pre>
 *   降级率 = 降级 / 总数
 * </pre>
 * <b>{@code 失败} 不计入降级率</b> —— 提问被敏感词拦下是**设计内的行为**,
 * 不是 AI 服务出了问题。把它算进降级率,会让"有人试图问违规内容"这件事
 * 看起来像"AI 挂了",排查时会去查余额和网络,而真正的原因在词库里。
 * 三个计数都单独给出,想让它们进分母的人自己除。
 *
 * <p>三个数字放在一起才有诊断价值:降级率突然升高 + 原因分布里"鉴权失败401"
 * 占大头 → 密钥出问题了;全是"超时" → 该调超时或者查网络;
 * 全是"余额不足402" → 该充值了。单看一个降级率只知道"有问题",不知道问题在哪。
 */
@Data
public class AiDegradeStatVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 统计范围内的提问总数 */
    private long total;

    private long successCount;

    private long degradedCount;

    private long failedCount;

    /** 降级率(百分比,保留一位小数)= 降级 / 总数 */
    private BigDecimal degradeRate;

    /** 平均耗时(毫秒),只统计成功的那部分 —— 降级的耗时没有参考价值 */
    private long avgCostMs;

    /** 最慢一次(毫秒) */
    private long maxCostMs;

    /** 累计消耗的 token(提示 + 生成) */
    private long totalTokens;

    /** 降级原因的分布,按次数倒序。成功记录的原因为空,不在这里 */
    private List<AiDegradeReasonVO> reasons;
}
