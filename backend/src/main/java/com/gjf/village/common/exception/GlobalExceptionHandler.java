package com.gjf.village.common.exception;

import com.gjf.village.common.result.Result;
import com.gjf.village.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>把各类异常统一转换成 {@link Result} 结构,保证前端永远只需判断 code。
 *
 * <p><b>两条原则:</b>
 * <ol>
 *   <li>业务异常(用户能看懂、能自己解决)只记 warn,不打印堆栈 —— 避免日志被刷屏</li>
 *   <li>系统异常必须打完整堆栈,否则线上排查无从下手</li>
 * </ol>
 *
 * <p><b>安全约束:</b>系统异常不把原始异常信息返回给前端,
 * 只返回统一话术,避免泄露 SQL、表名、内部路径等敏感信息。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常 —— 可预期的、面向用户的错误。
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常 [{} {}]: code={}, message={}",
                request.getMethod(), request.getRequestURI(), e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * @Valid 校验失败(RequestBody 场景)。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        return Result.error(ResultCode.BAD_REQUEST, message);
    }

    /**
     * @Valid 校验失败(表单绑定场景)。
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败: {}", message);
        return Result.error(ResultCode.BAD_REQUEST, message);
    }

    /**
     * 缺少必填请求参数。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return Result.error(ResultCode.BAD_REQUEST, "缺少必填参数:" + e.getParameterName());
    }

    /**
     * 请求体无法解析(通常是前端传了非法 JSON)。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.error(ResultCode.BAD_REQUEST, "请求体格式有误");
    }

    /**
     * 请求方法不支持(比如用 GET 调了 POST 接口)。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {}", e.getMessage());
        return Result.error(ResultCode.BAD_REQUEST, "请求方法不支持:" + e.getMethod());
    }

    /**
     * 接口不存在。
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandlerFound(NoHandlerFoundException e) {
        log.warn("接口不存在: {} {}", e.getHttpMethod(), e.getRequestURL());
        return Result.error(ResultCode.NOT_FOUND, "接口不存在");
    }

    /**
     * 数据库约束冲突 —— 外键挡住删除、唯一索引撞车等。
     *
     * <p>最常见的一种:删一个还被别的表引用的村组。数据库的外键是
     * {@code ON DELETE RESTRICT},业务层没有(也不可能)把每张引用表都数一遍,
     * 于是最终会由数据库拦下来。
     *
     * <p>不接这个异常的话,它会掉进下面的兜底处理器,变成一句
     * "系统繁忙,请稍后重试" —— 管理员看到这句话只会反复重试,
     * 而真正该做的是去把引用改掉。所以这里翻译成人能看懂的话。
     *
     * <p>原始消息仍然只进日志不进响应:里面带着表名、约束名,
     * 对用户没用,对外人是情报。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public Result<Void> handleDataIntegrity(DataIntegrityViolationException e,
                                            HttpServletRequest request) {
        log.warn("数据约束冲突 [{} {}]: {}", request.getMethod(), request.getRequestURI(),
                e.getMostSpecificCause().getMessage());
        return Result.error(ResultCode.BAD_REQUEST,
                "该数据还被其他记录引用,无法删除或修改。请先处理掉引用它的数据");
    }

    /**
     * 兜底 —— 所有未被上面处理的异常。
     *
     * <p>归一化为 500,且<b>不</b>把原始异常消息返回前端(防信息泄露)。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常 [{} {}]", request.getMethod(), request.getRequestURI(), e);
        return Result.error(ResultCode.INTERNAL_ERROR);
    }
}
