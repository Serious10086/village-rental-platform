package com.gjf.village.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 记录一次操作到 {@code sys_operation_log}。
 *
 * <p>用法:{@code @Log(module = "资源审核", operation = "审核通过")}。
 *
 * <h3>标在哪里</h3>
 *
 * <p><b>标在 Controller 的方法上,不要标在 Service 上。</b>两个理由:
 * <ul>
 *   <li>切面在 Controller 层拿到的是**完整的 HTTP 请求**(URL、参数、IP) ——
 *       到了 Service 层这些上下文要么丢了,要么得从 ThreadLocal 里捞。</li>
 *   <li>事务边界在 Service 上。切面包在 Controller 外面,所以"业务事务回滚了"
 *       和"日志记下了这次失败"两件事互不干扰 —— 反过来把日志写进业务事务里,
 *       一次失败回滚会把"这次失败"的日志也一起回滚掉,那就永远查不到坏账。</li>
 * </ul>
 *
 * <h3>只标写操作</h3>
 *
 * <p>审核、删除、调分、发布、下架这些会改变系统状态的动作才值得记。
 * 查询不记 —— 列表接口一天被翻几百次,记下来只会把日志表撑大、
 * 把真正要查的那几条淹掉。<b>日志的价值在于"能查",不在于"记得多"。</b>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Log {

    /** 模块名,用于按模块筛。例如「资源审核」「留言管理」 */
    String module();

    /** 操作描述,直接展示给人看。例如「审核通过」「下架资源」 */
    String operation();
}
