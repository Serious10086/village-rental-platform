package com.gjf.village.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 调用线程池。
 *
 * <h3>为什么"调用第三方接口"要单独一个池</h3>
 *
 * <p>智能客服是全系统唯一一个<b>会等好几秒、而且等的是外部服务</b>的接口。
 * 不隔离的话,二十个人同时提问就会占住二十个 Tomcat 线程,而它们什么都不做,
 * 只是在等 DeepSeek 回话 —— 紧接着平台上其他所有接口一起变慢,
 * 罪魁祸首看起来却是"AI 慢了"。
 *
 * <p>这个池做三件事:
 * <ol>
 *   <li><b>限流(bulkhead)</b> —— 最多 4 个请求同时在飞。DeepSeek 按量计费
 *       也有限流,把并发放大几十倍只会换来一片 429。</li>
 *   <li><b>削峰(队列)</b> —— 排 50 个。突发流量先排队,而不是直接打过去。</li>
 *   <li><b>背压(CallerRunsPolicy)</b> —— 队列也满了,就让提交任务的那个
 *       Tomcat 线程自己跑。这时请求变慢(它要亲自等 AI),
 *       但<b>不会丢</b>,客户端那边自然就慢下来了 —— 比直接拒绝要好。</li>
 * </ol>
 *
 * <h3>为什么不是"发出去就不管"(fire-and-forget)</h3>
 *
 * <p>那样服务端确实少占一个线程,但要换来三样东西:前端得轮询结果、
 * "正在思考"的状态要额外维护、而且用户关闭页面之后那个任务还在花钱。
 * 用它换"少占一个线程"不划算 —— 真正的解药是上面那三条(限流 + 队列 + 背压),
 * 它们和"同步等待"并不冲突。
 *
 * <h3>几个数字是怎么定的</h3>
 *
 * <p>{@code core=2 / max=4}:一次调用最长 10 秒,4 路并发时最坏能吞掉
 * 约 40 秒的排队量;村级平台的提问量远低于这个数,再多就是浪费连接。
 * <p>{@code queue=50}:按每问平均 2 秒算,50 条大约是一分多钟的积压量。
 * 到这个程度,C 端早就没有耐心的存量了,再排队没有意义。
 * <p>{@code keepAlive=60s} + {@code allowCoreThreadTimeOut}:空闲时线程会退掉。
 * 演示环境一天可能就几十次提问,没必要常驻两个线程。
 */
@Slf4j
@Configuration
public class AsyncConfig {

    /** Bean 名字 —— 注入时用 {@code @Qualifier(AsyncConfig.AI_EXECUTOR)} */
    public static final String AI_EXECUTOR = "aiExecutor";

    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 50;
    private static final long KEEP_ALIVE_SECONDS = 60L;

    @Bean(name = AI_EXECUTOR, destroyMethod = "shutdown")
    public ThreadPoolExecutor aiExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                namedThreadFactory(),
                // 队列满了不是抛异常,而是让调用方线程自己执行 ——
                // 拒绝的话用户会收到一个"系统繁忙"的 500,
                // 而 AI 这块本来就准备了兜底话术,慢一点也比报错强
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        log.info("AI 调用线程池已创建: core={}, max={}, queue={}, 拒绝策略=CallerRunsPolicy",
                CORE_POOL_SIZE, MAX_POOL_SIZE, QUEUE_CAPACITY);
        return executor;
    }

    /**
     * 给线程起个能认出来的名字。
     *
     * <p>不命名的话,线程池里跑的是 {@code pool-3-thread-1} 这种东西 ——
     * 线上打一次 jstack,满屏都是 pool-N-thread-M,看不出哪几个线程正卡在
     * 等 DeepSeek 上。命名之后一眼就能数出"现在有几个 AI 调用在飞"。
     */
    private ThreadFactory namedThreadFactory() {
        AtomicInteger seq = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "ai-call-" + seq.getAndIncrement());
            // 非守护线程:关服时让已经发出去的调用把结果处理完,
            // 而不是半路被砍掉、留下一条写不完整的对话记录
            thread.setDaemon(false);
            return thread;
        };
    }
}
