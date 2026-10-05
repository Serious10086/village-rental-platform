package com.gjf.village.ai;

/**
 * AI 调用失败。
 *
 * <p>带两样东西:{@link AiFailure 归因}和<b>这次失败值不值得重试</b>。
 *
 * <p><b>为什么"要不要重试"放在异常实例上,而不是挂在枚举上:</b>
 * 同一个 {@link AiFailure#TIMEOUT},连接的 3 秒超时重试一次是划算的
 * (一秒就能重连上),而读取的 10 秒超时重试一次就是让用户再等 10 秒 ——
 * 归因相同,处置相反。重试与否是这一次失败的性质,不是这一类失败的性质。
 */
public class AiCallException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final AiFailure failure;

    /** 本次失败是否值得重试(由抛出点按具体情况判断) */
    private final boolean retryable;

    public AiCallException(AiFailure failure, boolean retryable, String message) {
        super(message);
        this.failure = failure;
        this.retryable = retryable;
    }

    public AiCallException(AiFailure failure, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
        this.retryable = retryable;
    }

    public AiFailure failure() {
        return failure;
    }

    public boolean retryable() {
        return retryable;
    }
}
