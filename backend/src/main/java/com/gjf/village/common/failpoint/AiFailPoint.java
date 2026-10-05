package com.gjf.village.common.failpoint;

import com.gjf.village.ai.AiFailure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 调用失败点(故障注入)。
 *
 * <p><b>它解决的测试问题和 {@link TransactionFailPoint} 是同一个:</b>
 * 验收项 AI-02(超时降级)和 AI-03(密钥失效降级)要断言的是
 * "AI 调不通时返回兜底话术、记录降级原因、<b>而不是 500</b>",
 * 可是代码正确的时候 AI 不会调不通 —— 想让它调不通,只能去改配置
 * (把超时改成 1 毫秒、把密钥改错)然后重启。那样一来,测的是"改过配置的
 * 那一份",而不是要发布的那一份;而且重启一次只能测一条,没法进回归。
 *
 * <p>所以这里埋一个失败点:平时什么都不做,由超管装载后
 * <b>下一个提问</b>会被强制判定为指定的失败类型,然后走和真实失败
 * 完全相同的降级路径。
 *
 * <p><b>安全设计和事务失败点一致:</b>默认不装载(常态是一条空判断)、
 * 一次性(触发后自动清除,不会因为忘了关而让所有提问持续降级)、
 * 仅超管可装载。<b>即便被恶意装载,后果也只是"某一次提问返回了兜底话术"</b> ——
 * 不会损坏数据,也不会泄露任何东西。
 */
@Slf4j
@Component
public class AiFailPoint {

    /**
     * 已装载的失败类型。<b>null 表示没有装载</b>,这是常态。
     *
     * <p>用 {@code getAndSet(null)} 而不是先读后写:并发下两个提问可能
     * 同时读到同一个值、双双降级。原子替换保证"装载一次只影响一个请求"。
     */
    private final AtomicReference<AiFailure> armed = new AtomicReference<>();

    /** 装载:下一个提问会被强制判定为这种失败 */
    public void arm(AiFailure failure) {
        armed.set(failure);
        log.warn("【故障注入】已装载 AI 失败点: {},下一个提问将走降级路径", failure.reason());
    }

    /** 当前装载的失败类型,未装载返回 null */
    public AiFailure armedFailure() {
        return armed.get();
    }

    /** 清除装载 */
    public void clear() {
        armed.set(null);
        log.warn("【故障注入】AI 失败点已清除");
    }

    /**
     * 消费一次。
     *
     * @return 已装载的失败类型并立即清除;返回 null 表示没有装载(常态)
     */
    public AiFailure consume() {
        return armed.getAndSet(null);
    }
}
