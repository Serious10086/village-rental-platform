package com.gjf.village.annotation;

import java.lang.annotation.*;

/**
 * 按钮级权限校验注解。
 *
 * <p>用法:
 * <pre>
 *   &#64;RequiresPerm("resource:audit")
 *   &#64;PostMapping("/audit")
 *   public Result&lt;Void&gt; audit(...) { ... }
 * </pre>
 *
 * <p>多个权限标识之间是 <b>或</b> 的关系 —— 只要拥有其中一个就放行。
 *
 * <p><b>为什么前端隐藏了按钮,后端还必须再校验一次:</b>
 * 前端隐藏按钮只是"不给用户看见",挡不住任何人直接用 Postman
 * 或者浏览器控制台发一个 HTTP 请求。接口安全必须在服务端兜住,
 * 前端隐藏只是体验优化。这是两层防护,不是重复劳动。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPerm {

    /** 权限标识,如 resource:audit;多个之间为"或"关系 */
    String[] value();
}
