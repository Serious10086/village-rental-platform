package com.gjf.village.service;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.dto.AiChatQueryDTO;
import com.gjf.village.vo.AiAnswerVO;
import com.gjf.village.vo.AiChatRecordVO;
import com.gjf.village.vo.AiDegradeStatVO;

/**
 * AI 智能客服服务。
 *
 * <p>一次问答的完整链路:<b>内容安全 → 取上下文 → 调模型 → 出口检查 →
 * 兜底 → 落库</b>。每一步都有它必须在那里出现的理由,顺序也不是随意的,
 * 具体见实现里的注释。
 */
public interface AiChatService {

    /**
     * 问一句,拿一个回答。
     *
     * <p><b>保证「一定有一个回答」</b>:这个方法不会因为 AI 挂了而抛异常,
     * 调用方拿到的 {@code answer} 永远非空。失败被表达在
     * {@code status} 和 {@code degradeReason} 里,而不是异常里 ——
     * 智能客服答不上来是**业务上的正常结果**,让前端去处理
     * "有时候返回 200 有时候抛 500",是把复杂度推给了本来就更弱的一侧。
     *
     * @param sessionId 会话ID(前端生成的 UUID),多轮上下文按它聚合
     * @param question  用户提问
     */
    AiAnswerVO chat(String sessionId, String question);

    /** 用户点「已解决」 */
    void markResolved(Long recordId);

    // ── 后台 ────────────────────────────────────────────────────

    /** 会话记录列表(受数据级权限约束) */
    PageResult<AiChatRecordVO> pageAdmin(AiChatQueryDTO query);

    /** 降级率与原因分布(同一套数据级权限) */
    AiDegradeStatVO degradeStat(AiChatQueryDTO query);

    /**
     * 标记为「需要人工跟进」。
     *
     * <p>说清楚它<b>不是</b>什么:系统里没有在线的人工客服,这个动作不会把会话
     * 转给谁、也不会通知谁。它是给管理员看的一个待办标记 ——
     * 后台可以据此筛出"AI 没答好、需要有人跟进一下"的记录。
     */
    void markFollowUp(Long recordId);
}
