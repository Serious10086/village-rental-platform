package com.gjf.village.ai;

/**
 * AI 调用失败的归因。
 *
 * <p><b>这个枚举的每一个取值,都是为了让"降级原因"能落到一句人话上。</b>
 * {@link #reason} 会原样写进 {@code ai_chat_record.degrade_reason},
 * 后台的降级率统计按它分组 —— 没有它,降级记录只能写一句"调用失败",
 * 排查时不知道该去查余额、查网络、还是查密钥。
 *
 * <p>取值和 {@code ai_chat_record} 建表时约定的那几种对齐
 * (超时 / 限流429 / 余额不足402 / 网络异常),另外补了两个建表时没想到、
 * 但实现时一定会遇到的:密钥没配、鉴权失败。后者是验收项"Key 失效降级"
 * 要用的那一种,不能让它和"网络异常"混在一起 —— 一个是运维问题,
 * 一个是配置问题,处理方式完全不同。
 */
public enum AiFailure {

    /** 连不上、或者读超时 */
    TIMEOUT("超时"),

    /** HTTP 429,被限流了 —— 等一会儿重试有意义 */
    RATE_LIMIT("限流429"),

    /** HTTP 402,账户余额不足 —— 重试多少次都一样 */
    INSUFFICIENT_BALANCE("余额不足402"),

    /** HTTP 401,密钥无效 —— 验收项 AI-03 要演示的就是这一种 */
    AUTH("鉴权失败401"),

    /** HTTP 400/422,请求本身不合法(比如 messages 为空) */
    BAD_REQUEST("请求参数错误"),

    /** HTTP 5xx,DeepSeek 那侧的问题 */
    SERVER_ERROR("服务端错误"),

    /** 连不上:DNS、代理、防火墙、断网 */
    NETWORK("网络异常"),

    /** 通了,但响应不是我们预期的结构,或者回答是空的 */
    BAD_RESPONSE("响应格式异常"),

    /**
     * 压根没有密钥。
     *
     * <p>单独拎出来,是因为它不是"调用失败"而是"没打算调用" ——
     * 别人 clone 了仓库、还没照 {@code application-dev.yml.example}
     * 建自己的配置时,就会走到这里。这种情况下去发一个
     * {@code Authorization: Bearer }(空) 的请求,拿回来的 401
     * 会指向"密钥无效",而真正的原因是"密钥没填",能让人白查半天。
     */
    NOT_CONFIGURED("未配置密钥");

    private final String reason;

    AiFailure(String reason) {
        this.reason = reason;
    }

    /** 写进 {@code ai_chat_record.degrade_reason} 的文本 */
    public String reason() {
        return reason;
    }
}
