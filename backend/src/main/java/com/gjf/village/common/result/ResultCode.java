package com.gjf.village.common.result;

import lombok.Getter;

/**
 * 统一响应状态码。
 *
 * <p>约定:
 * <ul>
 *   <li>200 成功</li>
 *   <li>4xx 客户端错误(参数、认证、权限)</li>
 *   <li>5xx 服务端错误</li>
 *   <li>1xxx 业务语义错误(HTTP 状态码仍是 200,由前端按 code 判断)</li>
 * </ul>
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "操作成功"),

    // ── 客户端错误 ──────────────────────────────────────────────
    BAD_REQUEST(400, "请求参数有误"),
    UNAUTHORIZED(401, "登录已失效,请重新登录"),
    FORBIDDEN(403, "没有该操作权限"),
    NOT_FOUND(404, "请求的资源不存在"),

    // ── 服务端错误 ──────────────────────────────────────────────
    INTERNAL_ERROR(500, "系统繁忙,请稍后重试"),

    // ── 业务错误 ────────────────────────────────────────────────
    /** 账号或密码错误 */
    LOGIN_FAILED(1001, "手机号或密码错误"),
    /** 账号被禁用或封禁 */
    ACCOUNT_DISABLED(1002, "账号已被禁用,请联系管理员"),
    /** 手机号已注册 */
    PHONE_EXISTS(1003, "该手机号已注册"),
    /** 实名认证未通过,不能执行该操作 */
    REALNAME_REQUIRED(1004, "请先完成实名认证"),
    /** 信用分不足 */
    CREDIT_TOO_LOW(1005, "信用分不足,暂时无法进行该操作"),
    /** 资源状态不允许该操作 */
    RESOURCE_STATUS_INVALID(1006, "资源当前状态不允许该操作"),
    /** 租赁时段冲突 */
    RENT_TIME_CONFLICT(1007, "所选租赁时段已被占用,请更换日期"),
    /** 订单状态不允许该操作 */
    ORDER_STATUS_INVALID(1008, "订单当前状态不允许该操作"),
    /** 余额不足 */
    BALANCE_NOT_ENOUGH(1009, "账户余额不足"),
    /** 内容含敏感词被拦截 */
    SENSITIVE_CONTENT(1010, "内容包含违规信息,请修改后重试"),
    /** AI 服务不可用(已降级) */
    AI_UNAVAILABLE(1011, "智能客服暂时繁忙,请稍后再试或留言");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
