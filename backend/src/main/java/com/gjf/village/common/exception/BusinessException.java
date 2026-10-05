package com.gjf.village.common.exception;

import com.gjf.village.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>用于表达"请求本身合法、但业务规则不允许"的场景,例如:
 * 租赁时段冲突、余额不足、订单状态不允许该操作等。
 *
 * <p><b>为什么继承 RuntimeException 而不是 Exception:</b>
 * Spring 的 {@code @Transactional} 默认只对 RuntimeException 回滚。
 * 如果这里继承 Exception,业务异常抛出时事务不会回滚,会留下脏数据。
 * 虽然项目里统一写了 {@code rollbackFor = Exception.class} 兜底,
 * 但继承 RuntimeException 是更稳妥的第一道防线 —— 少写一次注解也不会出事。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.INTERNAL_ERROR.getCode();
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 抛出业务异常(静态工厂,让调用处少一行 new)。
     */
    public static BusinessException of(ResultCode resultCode) {
        return new BusinessException(resultCode);
    }

    public static BusinessException of(ResultCode resultCode, String message) {
        return new BusinessException(resultCode, message);
    }

    // ── 常用异常的快捷构造 ──────────────────────────────────────

    public static BusinessException forbidden(String message) {
        return new BusinessException(ResultCode.FORBIDDEN, message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(ResultCode.NOT_FOUND, message);
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(ResultCode.BAD_REQUEST, message);
    }
}
