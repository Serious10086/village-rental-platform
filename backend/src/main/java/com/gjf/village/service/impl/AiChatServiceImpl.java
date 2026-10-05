package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gjf.village.ai.AiCallException;
import com.gjf.village.ai.AiFailure;
import com.gjf.village.ai.DeepSeekClient;
import com.gjf.village.ai.FallbackFaq;
import com.gjf.village.ai.PromptTemplate;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.failpoint.AiFailPoint;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.util.SensitiveWordUtil;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.config.AsyncConfig;
import com.gjf.village.dto.AiChatQueryDTO;
import com.gjf.village.entity.AiChatRecord;
import com.gjf.village.entity.SysVillage;
import com.gjf.village.mapper.AiChatRecordMapper;
import com.gjf.village.mapper.SysVillageMapper;
import com.gjf.village.security.LoginUser;
import com.gjf.village.service.AiChatService;
import com.gjf.village.service.DataScopeHelper;
import com.gjf.village.vo.AiAnswerVO;
import com.gjf.village.vo.AiChatRecordVO;
import com.gjf.village.vo.AiDegradeStatVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * AI 智能客服服务实现。
 *
 * <h3>一次问答的六个步骤,顺序不能换</h3>
 *
 * <pre>
 *   ① 入口内容安全 —— 提问命中拦截类词就直接拒绝
 *   ② 取上下文     —— 同一个会话里最近几轮真实问答
 *   ③ 调模型       —— 在线程池里,带硬期限
 *   ④ 出口内容安全 —— AI 的回答也要检查
 *   ⑤ 兜底         —— 没成功就给本地能给的答案(三级降级)
 *   ⑥ 落库         —— 不论成功、降级还是拦截,都留一条记录
 * </pre>
 *
 * <p><b>①必须在③之前:拦下的提问不出网,也就不花钱。</b>
 * 反过来的话,一次违规提问会先花掉一次调用,再把它拦下来。
 *
 * <h3>这个方法上为什么没有 @Transactional</h3>
 *
 * <p>它里面有一次最长 10 秒的外部调用。<b>把外部调用放进事务,等于让一个数据库
 * 连接陪着一起等 10 秒</b> —— 连接池是有限的(本项目的 HikariCP 是 20 个),
 * 几十个人同时提问就能把连接池占满,然后全站所有接口一起挂。
 * 这里只有一条 insert,它自己就是原子的,不需要事务包着。
 * 这是整个项目里"哪些东西绝不能进事务"的第一个例子(第二个是文件上传)。
 */
@Slf4j
@Service
public class AiChatServiceImpl implements AiChatService {

    /** 带几轮历史。5 轮足够接住"那押金呢?"这类追问,再多只是白花 token */
    private static final int CONTEXT_ROUNDS = 5;

    /** 拦截类敏感词命中时的降级原因 */
    private static final String REASON_BLOCKED_QUESTION = "提问包含违规词";

    /** AI 的回答里出现拦截类敏感词时的降级原因 */
    private static final String REASON_BLOCKED_ANSWER = "AI回答包含违规词";

    /** 没配密钥时的模型列取值 —— 那一列 NOT NULL,必须写点什么 */
    private static final String MODEL_NOT_CALLED = "(未调用)";

    private final AiChatRecordMapper recordMapper;
    private final SysVillageMapper villageMapper;
    private final DeepSeekClient deepSeekClient;
    private final FallbackFaq fallbackFaq;
    private final SensitiveWordUtil sensitiveWordUtil;
    private final DataScopeHelper dataScopeHelper;
    private final AiFailPoint aiFailPoint;

    /**
     * AI 调用线程池。
     *
     * <p>{@code @Qualifier} 现在只有一个候选,写了看着多余 —— 但线程池这种东西
     * 迟早会出现第二个(导出、批量通知都用得上),到那天不加限定的注入会
     * 直接报"找到了多个候选",而报错信息不会告诉你该给哪一个。
     */
    private final ThreadPoolExecutor aiExecutor;

    /**
     * 等待 AI 结果的硬期限。
     *
     * <p>比客户端的读取超时多 3 秒 —— 客户端自己会在第 10 秒放弃并给出
     * "读取超时"这个精确原因,这里只是**兜住"连线程都没轮到"的情况**
     * (队列排满了、池被占满了)。如果这里先超时,说明问题不在 DeepSeek,
     * 而在本机的排队上。
     */
    private final int deadlineSeconds;

    public AiChatServiceImpl(AiChatRecordMapper recordMapper,
                             SysVillageMapper villageMapper,
                             DeepSeekClient deepSeekClient,
                             FallbackFaq fallbackFaq,
                             SensitiveWordUtil sensitiveWordUtil,
                             DataScopeHelper dataScopeHelper,
                             AiFailPoint aiFailPoint,
                             @Qualifier(AsyncConfig.AI_EXECUTOR) ThreadPoolExecutor aiExecutor,
                             @Value("${app.ai.deepseek.read-timeout-seconds:10}") int readTimeoutSeconds) {
        this.recordMapper = recordMapper;
        this.villageMapper = villageMapper;
        this.deepSeekClient = deepSeekClient;
        this.fallbackFaq = fallbackFaq;
        this.sensitiveWordUtil = sensitiveWordUtil;
        this.dataScopeHelper = dataScopeHelper;
        this.aiFailPoint = aiFailPoint;
        this.aiExecutor = aiExecutor;
        this.deadlineSeconds = readTimeoutSeconds + 3;
    }

    // ═══════════════════════════════════════════════════════════
    //  问答
    // ═══════════════════════════════════════════════════════════

    @Override
    public AiAnswerVO chat(String sessionId, String question) {
        LoginUser me = UserContext.get();
        String asked = question.trim();

        // ── ① 入口内容安全 ──────────────────────────────────────
        // 命中拦截类词:**直接拒绝,不往外发**。
        // 不只是为了省钱 —— 把违规内容送给第三方模型,本身就是一次
        // 不该发生的数据外发。日志里记 userId,不记完整提问
        // (完整提问可能就是他试图发出去的违规内容,日志不是它的容身之处)。
        if (sensitiveWordUtil.shouldBlock(asked)) {
            log.info("AI 提问被内容安全拦下: userId={}, 命中词={}",
                    me.getUserId(), sensitiveWordUtil.findAll(asked));
            return persist(sessionId, me, asked, fallbackFaq.blockedMessage(),
                    AiChatRecord.STATUS_FAILED, REASON_BLOCKED_QUESTION,
                    null, MODEL_NOT_CALLED, 0L);
        }

        long start = System.currentTimeMillis();
        String systemPrompt = PromptTemplate.systemPromptFor(villageNameOf(me));

        try {
            // ── 故障注入(只在测试时装载)────────────────────────
            // 抛出去而不是直接调 degrade,是为了让它走**和真实失败完全相同**
            // 的那条 catch —— 注入的意义就在于验证真实路径,绕过它就没意义了。
            // 平时 consume() 返回 null,这里是一条空判断,对正常业务零影响。
            AiFailure injected = aiFailPoint.consume();
            if (injected != null) {
                throw new AiCallException(injected, false, "【故障注入】由超管装载,用于验证降级路径");
            }

            // ── ② 上下文 + ③ 调模型 ─────────────────────────────
            List<DeepSeekClient.ChatMessage> history = loadHistory(sessionId, me.getUserId());
            DeepSeekClient.ChatResult result =
                    callWithDeadline(systemPrompt, history, asked);

            // ── ④ 出口内容安全 ──────────────────────────────────
            // 只查拦截类。替换类不打码,理由见下面 answerOrFallback 的注释
            if (sensitiveWordUtil.shouldBlock(result.answer())) {
                log.warn("AI 回答命中敏感词,整条丢弃改用兜底话术: userId={}, 命中词={}",
                        me.getUserId(), sensitiveWordUtil.findAll(result.answer()));
                return persist(sessionId, me, asked, answerOrFallback(asked),
                        AiChatRecord.STATUS_DEGRADED, REASON_BLOCKED_ANSWER,
                        result, result.actualModel(), System.currentTimeMillis() - start);
            }

            return persist(sessionId, me, asked, result.answer(),
                    AiChatRecord.STATUS_SUCCESS, null,
                    result, result.actualModel(), System.currentTimeMillis() - start);

        } catch (AiCallException e) {
            // ── ⑤ 三级降级:这里是第二级和第三级 ────────────────
            return degrade(sessionId, me, asked, e.failure(), e.getMessage(),
                    System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            // 兜底之上的兜底。走到这里说明是没预料到的异常(解析、序列化、
            // 或者将来某次改动的副作用)—— **但它同样不能让对话框卡住**,
            // 所以一律换成兜底话术,并把堆栈留在日志里
            log.error("AI 问答出现未预期异常,已按降级处理: userId={}", me.getUserId(), e);
            return degrade(sessionId, me, asked, AiFailure.BAD_RESPONSE, e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }

    /**
     * 降级:先试本地问答库(第二级),再退通用话术(第三级)。
     *
     * <p>分成两级而不是"直接说客服繁忙",是因为本地那十一条问答覆盖了
     * 绝大多数真实提问 —— 押金、租金、实名、提现。DeepSeek 挂了的时候,
     * 用户问的很可能正是这些,本地库能给出**和 AI 一样的答案**。
     * 只有问到库里没有的,才需要承认答不上来。
     */
    private AiAnswerVO degrade(String sessionId, LoginUser me, String question,
                               AiFailure failure, String detail, long costMs) {
        Optional<String> local = fallbackFaq.answerFor(question);
        String answer = local.orElseGet(fallbackFaq::busyMessage);

        log.warn("AI 问答降级: userId={}, 原因={}, 命中本地问答={}, 耗时={}ms, 详情={}",
                me.getUserId(), failure.reason(), local.isPresent(), costMs, detail);

        // 模型列写"这次打算用的模型" —— 那一列的意义是"这次用的是哪个模型",
        // 而不是"这次有没有调用成功"(后者由 status 表达)
        return persist(sessionId, me, question, answer,
                AiChatRecord.STATUS_DEGRADED, failure.reason(),
                null, deepSeekClient.configuration().model(), costMs);
    }

    /**
     * 在线程池里调模型,并给自己一个硬期限。
     *
     * <p>为什么要绕一层线程池:见 {@code AsyncConfig} 的注释 ——
     * 一句话是"别让几十个 Tomcat 线程一起站在门口等一个外部接口"。
     */
    private DeepSeekClient.ChatResult callWithDeadline(String systemPrompt,
                                                       List<DeepSeekClient.ChatMessage> history,
                                                       String question) {
        Future<DeepSeekClient.ChatResult> future =
                aiExecutor.submit(() -> deepSeekClient.chat(systemPrompt, history, question));
        try {
            return future.get(deadlineSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AiCallException(AiFailure.TIMEOUT, false,
                    "等待 AI 结果超过 " + deadlineSeconds + " 秒", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AiCallException ace) {
                // 客户端已经归因过了(超时/限流/欠费/鉴权),原样往上抛,
                // 不要在这里改写成"未预期异常" —— 那会把一个能直接指导
                // 排查的信息(比如"余额不足402")抹成一个笼统的失败
                throw ace;
            }
            throw new AiCallException(AiFailure.BAD_RESPONSE, false,
                    "AI 调用抛出未预期异常:" + (cause == null ? e.getMessage() : cause.getMessage()), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiCallException(AiFailure.NETWORK, false, "等待 AI 结果时被中断", e);
        }
    }

    /**
     * 拼最近几轮上下文。
     *
     * <p>查询是倒序的(最近的在前),拼进 messages 要翻成正序 ——
     * 模型看到的时间顺序反了,会把回答当成提问的上下文来理解,
     * 表现是"它答非所问,但你看不出为什么"。
     */
    private List<DeepSeekClient.ChatMessage> loadHistory(String sessionId, Long userId) {
        List<AiChatRecord> recent =
                recordMapper.selectRecentBySession(sessionId, userId, CONTEXT_ROUNDS);
        if (recent.isEmpty()) {
            return List.of();
        }
        List<DeepSeekClient.ChatMessage> messages = new ArrayList<>(recent.size() * 2);
        for (int i = recent.size() - 1; i >= 0; i--) {
            AiChatRecord record = recent.get(i);
            messages.add(new DeepSeekClient.ChatMessage("user", record.getQuestion()));
            messages.add(new DeepSeekClient.ChatMessage("assistant", record.getAnswer()));
        }
        return messages;
    }

    /**
     * AI 答不了的时候,先看本地问答库。
     *
     * <p><b>出口这里只做拦截,不做替换类打码</b>,和留言板、站内信不一样。
     * 那两处打码是因为"内容是用户写的、要保留事实以便复核";
     * AI 的回答不是证据,它只是一个即时生成的回复。把里面几个词打成星号,
     * 换来的是一段用户读不通的话 —— 与其给一段打了码的回答,不如整条不要。
     */
    private String answerOrFallback(String question) {
        return fallbackFaq.answerFor(question).orElseGet(fallbackFaq::busyMessage);
    }

    // ═══════════════════════════════════════════════════════════
    //  落库
    // ═══════════════════════════════════════════════════════════

    /**
     * 写一条对话记录,并装配返回给前端的对象。
     *
     * <p><b>三种结局都要落库</b> —— 成功、降级、拦截。
     * 只记成功的话,后台的降级率永远是 0,那个页面就成了摆设;
     * 而且"这次为什么没答上来"最关键的信息(downgrade_reason)恰恰
     * 只在降级时才有。
     */
    private AiAnswerVO persist(String sessionId, LoginUser me, String question, String answer,
                              int status, String degradeReason,
                              DeepSeekClient.ChatResult result, String model, long costMs) {
        AiChatRecord record = new AiChatRecord();
        record.setSessionId(sessionId);
        record.setUserId(me.getUserId());
        record.setVillageId(me.getVillageId());
        record.setQuestion(question);
        record.setAnswer(answer);
        record.setModel(model);
        record.setPromptTokens(result == null ? 0 : result.promptTokens());
        record.setCompletionTokens(result == null ? 0 : result.completionTokens());
        record.setCostTime(costMs);
        record.setStatus(status);
        record.setDegradeReason(degradeReason);
        record.setIsResolved(0);
        record.setIsTransfer(0);
        recordMapper.insert(record);

        AiAnswerVO vo = new AiAnswerVO();
        vo.setRecordId(record.getId());
        vo.setSessionId(sessionId);
        vo.setQuestion(question);
        vo.setAnswer(answer);
        vo.setStatus(status);
        vo.setStatusName(statusName(status));
        vo.setDegradeReason(degradeReason);
        vo.setModel(model);
        vo.setPromptTokens(record.getPromptTokens());
        vo.setCompletionTokens(record.getCompletionTokens());
        vo.setCostTime(costMs);
        vo.setCreateTime(record.getCreateTime());

        log.info("AI 问答: userId={}, sessionId={}, status={}, 耗时={}ms, tokens={}+{}, 原因={}",
                me.getUserId(), sessionId, statusName(status), costMs,
                record.getPromptTokens(), record.getCompletionTokens(), degradeReason);
        return vo;
    }

    // ═══════════════════════════════════════════════════════════
    //  后台
    // ═══════════════════════════════════════════════════════════

    @Override
    public void markResolved(Long recordId) {
        AiChatRecord record = recordMapper.selectById(recordId);
        if (record == null) {
            throw BusinessException.notFound("对话记录不存在");
        }
        // 只能标记自己的 —— 别人的对话跟你没关系,而 recordId 是连着的整数,
        // 不校验的话从 1 开始遍历就能把所有人的记录标记一遍
        LoginUser me = UserContext.get();
        if (!me.getUserId().equals(record.getUserId())) {
            throw BusinessException.forbidden("只能标记自己的对话");
        }
        AiChatRecord patch = new AiChatRecord();
        patch.setId(recordId);
        patch.setIsResolved(1);
        recordMapper.updateById(patch);
    }

    @Override
    public PageResult<AiChatRecordVO> pageAdmin(AiChatQueryDTO query) {
        dataScopeHelper.apply(query);
        Page<AiChatRecordVO> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<AiChatRecordVO> result = recordMapper.selectAdminPage(page, query);
        result.getRecords().forEach(vo -> vo.setStatusName(statusName(vo.getStatus())));
        return PageResult.of(result);
    }

    @Override
    public AiDegradeStatVO degradeStat(AiChatQueryDTO query) {
        dataScopeHelper.apply(query);
        AiDegradeStatVO stat = recordMapper.selectStat(query);
        // 一条记录都没有的时候 selectStat 里的聚合会返回 null 之外的默认值,
        // 但 MyBatis 对"整行都是聚合结果"的映射在极端情况下可能给出 null,
        // 这里兜一下,让前端永远拿到一个能直接渲染的对象
        if (stat == null) {
            stat = new AiDegradeStatVO();
        }
        stat.setReasons(recordMapper.selectReasonDistribution(query));
        return stat;
    }

    @Override
    public void markFollowUp(Long recordId) {
        AiChatRecord record = recordMapper.selectById(recordId);
        if (record == null) {
            throw BusinessException.notFound("对话记录不存在");
        }
        // 后台动作,校验的是数据级权限而不是归属 —— 村管管本村,超管管全部
        LoginUser me = UserContext.get();
        if (!me.isSuperAdmin() && !dataScopeHelper.canAccessVillage(record.getVillageId())) {
            throw BusinessException.forbidden("只能跟进本村的对话记录");
        }
        AiChatRecord patch = new AiChatRecord();
        patch.setId(recordId);
        patch.setIsTransfer(1);
        recordMapper.updateById(patch);
        log.info("AI 对话标记为需人工跟进: id={}, 操作人={}", recordId, me.getUserId());
    }

    // ═══════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════

    /**
     * 状态中文名。
     *
     * <p>在服务端映射而不是前端:{@code status} 是后端定义的一组码,
     * 让前端再写一遍 {@code if status === 1 ...}，改一处忘一处的时候
     * 就会出现"后台写着成功、前台写着降级"。
     */
    private static String statusName(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case AiChatRecord.STATUS_SUCCESS -> "成功";
            case AiChatRecord.STATUS_DEGRADED -> "降级";
            case AiChatRecord.STATUS_FAILED -> "失败";
            default -> "未知";
        };
    }

    /** 取用户所在村组的名字,取不到就返回 null(提示词里那一句就不加) */
    private String villageNameOf(LoginUser me) {
        if (me.getVillageId() == null) {
            return null;
        }
        SysVillage village = villageMapper.selectById(me.getVillageId());
        return village == null ? null : village.getVillageName();
    }
}
