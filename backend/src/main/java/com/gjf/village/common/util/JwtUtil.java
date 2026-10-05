package com.gjf.village.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具。
 *
 * <p><b>用的是 jjwt 0.12.7,不是实训项目里的 0.9.1。</b>两者 API 完全不同:
 * <pre>
 *   0.9.1(旧): Jwts.builder().setSubject(x).signWith(SignatureAlgorithm.HS256, secretString)
 *              Jwts.parser().setSigningKey(secretString).parseClaimsJws(token)
 *   0.12.x(今): Jwts.builder().subject(x).signWith(SecretKey)
 *              Jwts.parser().verifyWith(SecretKey).build().parseSignedClaims(token)
 * </pre>
 * 0.9.1 还依赖 javax.xml.bind,该模块在 Java 9+ 已被移除,在 Java 17 下必须手动补
 * jaxb-api 才能用 —— 那是 Java 8 时代的写法,本项目不再沿用。
 *
 * <p><b>token 里只放 userId,不放角色和权限。</b>原因:角色权限随时可能被超管调整,
 * 如果塞进 token,调整后要等 token 过期才生效。放 userId 的话每次请求重新装配,
 * 权限变更立即生效。
 */
@Slf4j
@Component
public class JwtUtil {

    /** HS256 要求密钥至少 256 位(32 字节),否则启动时抛 WeakKeyException */
    private final SecretKey secretKey;

    /** 有效期(分钟) */
    private final long expireMinutes;

    public JwtUtil(@Value("${app.jwt.secret}") String secret,
                   @Value("${app.jwt.expire-minutes:720}") long expireMinutes) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMinutes = expireMinutes;
    }

    /**
     * 生成 token。
     *
     * @param userId 用户ID
     * @param phone  手机号(仅用于排查问题,不参与鉴权)
     */
    public String generateToken(Long userId, String phone) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireMinutes * 60 * 1000);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("phone", phone)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析 token,返回其中的 userId。
     *
     * @return 解析成功返回 userId;token 非法或过期返回 null
     */
    public Long parseUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Long.valueOf(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            // 过期、签名不符、格式错误都走这里。
            // 打 debug 而不是 error —— token 过期是正常现象,不该刷满错误日志
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** token 有效期(分钟),供前端做续期提示用 */
    public long getExpireMinutes() {
        return expireMinutes;
    }
}
