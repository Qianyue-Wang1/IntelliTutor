package com.intellitutor.scut.common.utils;

import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * JWT 工具类（基于 jjwt 0.12.x）
 *
 * <p>
 * 负责 Access Token 的签发与解析校验，使用 HS256 对称签名。
 * <ul>
 * <li>签发：写入
 * {@code sub}（userId）、{@code roles}（角色列表）、{@code iat}、{@code exp}</li>
 * <li>解析：自动校验签名与过期，异常映射为 {@link ResultCode#TOKEN_INVALID} /
 * {@link ResultCode#TOKEN_EXPIRED}</li>
 * </ul>
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${intellitutor.jwt.secret}")
    private String secret;

    @Value("${intellitutor.jwt.expire-minutes}")
    private long expireMinutes;

    @Getter
    @Value("${intellitutor.jwt.token-header}")
    private String tokenHeader;

    @Getter
    @Value("${intellitutor.jwt.token-prefix}")
    private String tokenPrefix;

    private SecretKey signingKey;

    @PostConstruct
    public void init() {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 Access Token。
     *
     * @param userId 用户唯一标识
     * @param roles  用户角色列表
     * @return 签名后的 JWT 字符串
     */
    public String generateAccessToken(String userId, List<String> roles) {
        long nowMillis = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .claim("roles", roles)
                .issuedAt(new Date(nowMillis))
                .expiration(new Date(nowMillis + expireMinutes * 60 * 1000))
                .signWith(signingKey)
                .compact();
    }

    /**
     * 解析并校验 Access Token，返回 Claims 载荷。
     *
     * @param token JWT 字符串（不含 "Bearer " 前缀）
     * @return Claims 载荷
     * @throws BusinessException 签名无效或 Token 已过期时抛出
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.warn("[JWT] Token 已过期: {}", e.getMessage());
            throw new BusinessException(ResultCode.TOKEN_EXPIRED);
        } catch (JwtException e) {
            log.warn("[JWT] Token 解析失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.TOKEN_INVALID);
        }
    }

    /**
     * 从 Claims 中提取 userId（即 subject）。
     *
     * @param claims JWT 载荷
     * @return userId
     */
    public String getUserId(Claims claims) {
        return claims.getSubject();
    }

    /**
     * 从 Claims 中提取角色列表。
     *
     * @param claims JWT 载荷
     * @return 角色列表
     */
    @SuppressWarnings("unchecked")
    public List<String> getRoles(Claims claims) {
        return claims.get("roles", List.class);
    }
}
