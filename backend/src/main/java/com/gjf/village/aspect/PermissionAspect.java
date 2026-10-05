package com.gjf.village.aspect;

import com.gjf.village.annotation.RequiresPerm;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.result.ResultCode;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.security.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 按钮级权限校验切面。
 *
 * <p>与 {@link com.gjf.village.interceptor.JwtAuthInterceptor} 的分工:
 * 拦截器管"你是谁"(认证),本切面管"你能不能干这个"(授权)。
 *
 * <p><b>为什么用注解 + AOP 而不是 Spring Security 的 @PreAuthorize:</b>
 * 本系统的权限模型就是"权限码集合里有没有这个字符串",用 AOP 十几行就能说清楚,
 * 引入 Spring Security 要多配一整套过滤器链和 AuthenticationManager,
 * 而它带来的 OAuth2、表单登录等能力本项目用不到。论文里主动写明这个取舍即可。
 */
@Slf4j
@Aspect
@Component
public class PermissionAspect {

    @Before("@annotation(requiresPerm)")
    public void checkPermission(JoinPoint point, RequiresPerm requiresPerm) {
        LoginUser user = UserContext.get();

        // 超级管理员直接放行。
        //
        // 严格来说种子数据已经把全部菜单授给了 SUPER_ADMIN,它的 perms 集合是满的,
        // 这个判断是冗余的。但保留它有意义:以后新增了菜单和权限码,
        // 如果忘了给超管授权,超管会被自己的系统锁在门外 ——
        // 这是很尴尬且难排查的问题。多这一行可以避免。
        if (user.isSuperAdmin()) {
            return;
        }

        // 多个权限码之间是"或"关系
        String[] required = requiresPerm.value();
        boolean passed = Arrays.stream(required).anyMatch(user::hasPerm);

        if (!passed) {
            log.warn("权限校验未通过: userId={}, 接口={}, 需要={}, 实际拥有={}",
                    user.getUserId(),
                    point.getSignature().toShortString(),
                    Arrays.toString(required),
                    user.getPerms());
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "没有该操作权限,需要:" + String.join(" 或 ", required));
        }
    }
}
