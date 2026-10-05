package com.gjf.village.interceptor;

import com.gjf.village.common.util.JwtUtil;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.security.LoginUser;
import com.gjf.village.service.SysUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT 认证拦截器。
 *
 * <p>职责很简单,只有一件事:<b>把请求里的 token 翻译成 LoginUser 放进 ThreadLocal</b>。
 * 至于"你能不能访问这个接口"是 {@link com.gjf.village.aspect.PermissionAspect} 的事。
 * 拦截器负责"你是谁",AOP 负责"你能不能干这个",职责分开才好讲清楚。
 *
 * <p>注意它<b>不</b>区分前台后台 —— 只要带着有效 token 就装配用户身份。
 * 是否放行由各接口自己决定(白名单里的接口不登录也能访问,
 * 其余接口由 @RequiresPerm 或 Service 里的 UserContext.get() 兜住)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final SysUserService sysUserService;

    /**
     * 免登录即可访问的路径。
     *
     * <p>配置在 application.yml 里而不是写死 —— 后续批次会往里加
     * 资源列表、公告、敏感词过滤后的留言板等公开接口,改配置即可,不用动代码。
     */
    @Value("${app.security.whitelist}")
    private List<String> whitelist;

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 放行跨域预检请求。
        //    OPTIONS 请求不带 Authorization 头,拦下来会让所有跨域调用失败
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        // 2. 白名单路径直接放行(但仍尝试解析 token,
        //    这样公开接口也能知道"当前访问者是谁",比如资源列表要标记"我收藏过的")
        if (isWhitelisted(request.getRequestURI())) {
            tryLoadUser(request);
            return true;
        }

        // 3. 从 Header 取 token
        String token = extractToken(request);
        if (!StringUtils.hasText(token)) {
            // 不抛异常,交给后续流程处理 —— 没登录访问需要身份的接口时,
            // UserContext.get() 会抛 401。这样错误信息更准确
            // (比如"未登录" vs "登录已失效")
            return true;
        }

        // 4. 解析并装配用户
        Long userId = jwtUtil.parseUserId(token);
        if (userId == null) {
            // token 非法或已过期,同样不在这里拦 ——
            // 让真正需要身份的接口通过 UserContext.get() 报 401
            log.debug("token 无效或已过期, uri={}", request.getRequestURI());
            return true;
        }

        LoginUser loginUser = sysUserService.buildLoginUser(userId);
        if (loginUser != null) {
            UserContext.set(loginUser);
        }
        return true;
    }

    /**
     * 请求结束后清理 ThreadLocal。
     *
     * <p>这一句绝不能省。Tomcat 复用线程,不清理会导致:
     * ① ThreadLocal 上的对象无法回收,内存泄漏;
     * ② 下一个复用该线程的请求读到上一个用户的身份,造成越权。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    private void tryLoadUser(HttpServletRequest request) {
        String token = extractToken(request);
        if (!StringUtils.hasText(token)) {
            return;
        }
        Long userId = jwtUtil.parseUserId(token);
        if (userId != null) {
            LoginUser loginUser = sysUserService.buildLoginUser(userId);
            if (loginUser != null) {
                UserContext.set(loginUser);
            }
        }
    }

    /**
     * 从 Authorization 头里剥出 token。
     *
     * <p>标准格式是 {@code Authorization: Bearer <token>},
     * 但客户端偶尔会漏掉前缀,这里两种都兼容。
     */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (!StringUtils.hasText(header)) {
            return null;
        }
        if (header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        return header.trim();
    }

    private boolean isWhitelisted(String uri) {
        // 中文路径匹配用 UTF-8 解码,避免路径里的中文被错误解析
        String normalized = new String(uri.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        // 对每个模式做 trim:配置项是逗号分隔的字符串,书写时很容易带上空格,
        // 而 AntPathMatcher 对空格是敏感的(会把空格当成路径的一部分)。
        // 在这里统一清理,比要求每次改配置都小心翼翼可靠。
        return whitelist.stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .anyMatch(pattern -> PATH_MATCHER.match(pattern, normalized));
    }
}
