package com.gjf.village.common.util;

import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.result.ResultCode;
import com.gjf.village.security.LoginUser;

/**
 * 当前登录用户的线程上下文。
 *
 * <p><b>必须成对使用:</b>拦截器在 preHandle 里 {@code set},在 afterCompletion 里
 * {@code clear}。漏了 clear 会导致两个严重后果:
 * <ol>
 *   <li><b>内存泄漏</b> —— Tomcat 用线程池,线程不销毁,ThreadLocal 上的对象也一直不释放</li>
 *   <li><b>越权</b> —— 下一个复用该线程的请求可能读到上一个用户的身份,
 *       数据级权限直接失效。这是最危险的一类 bug</li>
 * </ol>
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /**
     * 取当前登录用户。未登录时抛 401,而不是返回 null ——
     * 返回 null 会让调用方到处写判空,漏一处就是一个空指针。
     */
    public static LoginUser get() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return user;
    }

    /**
     * 取当前登录用户,未登录时返回 null(给不需要强制登录的场景用)。
     */
    public static LoginUser getOrNull() {
        return HOLDER.get();
    }

    /** 当前用户ID,未登录抛 401 */
    public static Long getUserId() {
        return get().getUserId();
    }

    /** 当前用户的村组ID,可能为 null(未绑定村组的用户) */
    public static Long getVillageId() {
        return get().getVillageId();
    }

    public static boolean isSuperAdmin() {
        LoginUser user = HOLDER.get();
        return user != null && user.isSuperAdmin();
    }

    /**
     * 请求结束时必须调用,否则见类注释里的两个后果。
     */
    public static void clear() {
        HOLDER.remove();
    }
}
