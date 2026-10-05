package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gjf.village.dto.AiChatQueryDTO;
import com.gjf.village.entity.AiChatRecord;
import com.gjf.village.vo.AiChatRecordVO;
import com.gjf.village.vo.AiDegradeReasonVO;
import com.gjf.village.vo.AiDegradeStatVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI 对话记录 Mapper。
 */
@Mapper
public interface AiChatRecordMapper extends BaseMapper<AiChatRecord> {

    /**
     * 取某个会话最近的若干轮,<b>按时间倒序</b>。
     *
     * <p>倒序取再在 Java 里反转,而不是 SQL 里 {@code ORDER BY ASC LIMIT n} ——
     * 后者取到的是"最早的 n 轮",正好把最相关的最近几轮丢掉。
     * 想看最近几轮,只能先倒序取、再翻回来。
     *
     * <p><b>{@code userId} 不是可选的</b>:sessionId 由前端生成、可以被编造,
     * 只按 sessionId 取历史等于允许改一个字符串就读到别人的对话。
     * 会话归属谁,取历史就得连归属一起验。
     *
     * @param sessionId 会话ID
     * @param userId    会话归属人,必须传
     * @param limit     取几轮
     */
    List<AiChatRecord> selectRecentBySession(@Param("sessionId") String sessionId,
                                             @Param("userId") Long userId,
                                             @Param("limit") int limit);

    /**
     * 后台记录列表。
     *
     * <p>村组过滤来自 {@code DataScopeHelper} 注入的 {@code villageIds} 白名单,
     * 不是前端传的 {@code villageId} —— 超管的 scope 是"全部",此时
     * {@code villageIds} 为 null,{@code <if>} 不生效,自然就是不加限制。
     */
    IPage<AiChatRecordVO> selectAdminPage(IPage<AiChatRecordVO> page,
                                          @Param("q") AiChatQueryDTO query);

    /** 统计总览(同一套村组白名单)。没有记录时除 total 外全是 0,不会是 null */
    AiDegradeStatVO selectStat(@Param("q") AiChatQueryDTO query);

    /** 降级原因的分布,按次数倒序。成功记录的原因为空,不会出现在这里 */
    List<AiDegradeReasonVO> selectReasonDistribution(@Param("q") AiChatQueryDTO query);
}
