package com.gjf.village.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码加密配置。
 *
 * <p><b>这里只引入了 spring-security-crypto 这一个模块,不是完整的 Spring Security。</b>
 * 认证和授权由我们自己的拦截器 + AOP 实现(见 JwtAuthInterceptor / PermissionAspect)。
 * 单独用这个模块是因为 BCrypt 的实现质量足够好,没必要自己写加密,也不需要
 * 为此引入一整套过滤器链。
 *
 * <p>BCrypt 的两个关键特性:
 * <ol>
 *   <li><b>自带随机盐</b> —— 同一个密码每次加密得到的密文都不同,
 *       攻击者无法用彩虹表批量反查</li>
 *   <li><b>故意慢</b> —— 计算强度可调,暴力破解的代价远高于 MD5/SHA 这类快速哈希</li>
 * </ol>
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * 加密强度。
     *
     * <p>每 +1 计算量翻倍。10 是社区常用值(单次约 50-100ms),
     * 在"用户能接受"和"破解成本足够高"之间取平衡。
     */
    private static final int BCRYPT_STRENGTH = 10;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }
}
