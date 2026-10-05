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
 * AI 对话记录。
 *
 * <p><b>这张表同时是三样东西</b>,所以它的每一列都不是可有可无的:
 * <ol>
 *   <li><b>多轮对话的上下文来源</b> —— 按 {@code session_id} 取最近几轮
 *       拼进下一次提问。不存历史,AI 就接不住"那押金呢?"这种追问。</li>
 *   <li><b>降级的证据链</b> —— {@code status} + {@code degrade_reason}
 *       支撑后台的降级率统计。验收项 AI-07 要的就是这个。</li>
 *   <li><b>运营与排查的账本</b> —— {@code prompt_tokens} /
 *       {@code completion_tokens} / {@code cost_time} 让"这个月花了多少钱、
 *       慢在哪"有数可查。</li>
 * </ol>
 *
 * <p><b>注意本表没有 {@code deleted} 列,也不该有。</b>对话记录是只增不改的
 * 审计数据,和资金流水是同一类 —— 提供"删除"路径只会让证据链出现缺口。
 * 当前版本也确实没有任何删除入口。
 *
 * <p><b>思维链不在这里存。</b>客户端只取 {@code content} 作为回答,
 * {@code reasoning_content} 直接丢弃。存进来没有任何用处,而且是一份
 * 迟早会被误展示给用户的内容。
 */
@Data
@TableName("ai_chat_record")
public class AiChatRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ── 状态 ────────────────────────────────────────────────────
    /** AI 正常回答了 */
    public static final int STATUS_SUCCESS = 1;
    /** 降级:调用了但没成功(超时/限流/欠费/没配密钥),返回的是本地兜底话术 */
    public static final int STATUS_DEGRADED = 2;
    /**
     * 失败:这次请求没得到 AI 回答,而且<b>不是 AI 调用失败造成的</b>。
     *
     * <p>目前只有一种情况走到这里:提问被内容安全拦下 —— 压根没往外发,
     * 也没花钱。它和"降级"要分开计数,因为二者是两种完全不同的信号:
     * 降级率升高是<b>服务可用性</b>的问题(查余额、查网络、查密钥),
     * 拦截数升高是<b>内容安全</b>的问题(有人试图问违规内容)。
     * 混在一起的话,排查方向会被带偏。
     */
    public static final int STATUS_FAILED = 3;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 会话ID(前端生成的 UUID)。多轮上下文按它聚合 */
    private String sessionId;

    private Long userId;

    /** 用户所属村组。NULL 表示用户没绑定村组 */
    private Long villageId;

    /** 用户提问 */
    private String question;

    /**
     * 给用户看的那段话。
     *
     * <p>成功时是 AI 的回答;降级时是本地兜底话术;被拦截时是一句委婉的拒绝说明。
     * <b>三种情况都非空</b> —— 用户总该看到点什么,留一条 answer 为空的记录
     * 只意味着界面上是一片空白。
     */
    private String answer;

    /** 实际使用的模型 */
    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    /** 耗时(毫秒),含重试 */
    private Long costTime;

    /** 见上面的 STATUS_* */
    private Integer status;

    /** 降级原因,取自 {@code AiFailure.reason()};成功时为 NULL */
    private String degradeReason;

    /** 用户是否点了"已解决":1是 0否 */
    private Integer isResolved;

    /** 是否转人工:1是 0否 */
    private Integer isTransfer;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
