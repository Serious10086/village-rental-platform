package com.gjf.village.common.util;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务编号生成器。
 *
 * <p>生成形如 {@code R20260917000001} 的编号:R + 日期 + 6 位当日流水。
 *
 * <p><b>实现说明与局限性(值得在论文里写清楚):</b>
 *
 * <p>这里用内存计数器保证同一进程内不重复。它在单实例部署下是够用的
 * (毕设就是单实例),但有两个已知局限:
 * <ol>
 *   <li><b>多实例部署会撞号</b> —— 两个进程各自从 0 开始计数。
 *       生产环境的正确做法是用数据库序列表、Redis INCR,或者干脆
 *       只依赖数据库的唯一索引 + 重试。</li>
 *   <li><b>重启后会重复</b> —— 计数器归零,如果当天已经生成过编号,
 *       就会和已有数据冲突。</li>
 * </ol>
 *
 * <p>之所以仍然这样实现:第一,它足够简单且不引入额外依赖;
 * 第二,{@code biz_resource.resource_no} 上有唯一索引,
 * 万一撞号会被数据库直接拦下并抛异常,不会写入脏数据 ——
 * <b>兜底靠约束而不是靠生成逻辑本身</b>,这是更稳妥的设计习惯。
 */
@Component
public class BizNoGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 当前日期,用于判断是否需要重置计数器 */
    private volatile String currentDate = "";

    /**
     * 各前缀各自的当日流水计数器。
     *
     * <p>按前缀分开计数,而不是所有编号共用一个计数器 ——
     * 共用的话订单号会是 LO...000001、PAY...000002、PAY...000003 这样跳着走,
     * 截图和论文里引用的编号对不上,排查时也难判断"到底生成了几笔订单"。
     */
    private final Map<String, AtomicInteger> sequences = new ConcurrentHashMap<>();

    /**
     * 生成资源编号,如 {@code R20260917000001}。
     */
    public String nextResourceNo() {
        return generate("R");
    }

    /**
     * 生成订单编号,如 {@code LO20260917000001}。
     */
    public String nextOrderNo() {
        return generate("LO");
    }

    /**
     * 生成支付流水号,如 {@code PAY20260917000001}。
     */
    public String nextPaymentNo() {
        return generate("PAY");
    }

    /**
     * 生成模拟第三方渠道流水号。
     *
     * <p>前缀用 {@code CH} 而不是 {@code PAY},是为了让"平台内流水号"
     * 和"渠道流水号"在数据库里一眼能分开 —— 对账时这两者必须区分,
     * 混用前缀会让排查变成猜谜。
     */
    public String nextChannelTradeNo() {
        return generate("CH");
    }

    /**
     * 生成纠纷编号。
     */
    public String nextDisputeNo() {
        return generate("DP");
    }

    /**
     * 生成提现单号。
     */
    public String nextWithdrawNo() {
        return generate("WD");
    }

    private String generate(String prefix) {
        String today = LocalDate.now().format(DATE_FMT);
        synchronized (this) {
            // 跨天时所有计数器归零。不加这一句的话,次日的流水号会从昨天的最大值接着涨
            if (!today.equals(currentDate)) {
                currentDate = today;
                sequences.clear();
            }
        }
        int seq = sequences.computeIfAbsent(prefix, k -> new AtomicInteger(0)).incrementAndGet();
        return prefix + today + String.format("%06d", seq);
    }
}
