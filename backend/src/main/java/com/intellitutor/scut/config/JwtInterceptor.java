package com.intellitutor.scut.config;

import com.intellitutor.scut.common.constant.MqConstants;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.common.utils.JwtUtil;
import com.intellitutor.scut.common.utils.UserContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 鉴权拦截器。
 *
 * <p>
 * 拦截受保护的 API 请求，校验 {@code Authorization} 头中的 Bearer Token：
 * <ol>
 * <li>提取 Token 并解析 Claims</li>
 * <li>检查 Token 是否在黑名单（用户登出场景）</li>
 * <li>校验通过后将 userId 写入 {@link UserContext}</li>
 * </ol>
 *
 * <p>
 * 在 {@link #afterCompletion} 中清理 ThreadLocal，防止内存泄漏。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 放行 OPTIONS 预检请求（CORS）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String header = request.getHeader(jwtUtil.getTokenHeader());
        if (!StringUtils.hasText(header) || !header.startsWith(jwtUtil.getTokenPrefix())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        String token = header.substring(jwtUtil.getTokenPrefix().length());

        // 解析 Token（内部校验签名与过期）
        Claims claims = jwtUtil.parseToken(token);
        String userId = jwtUtil.getUserId(claims);

        // 检查 Token 黑名单
        String blacklistKey = MqConstants.TOKEN_BLACKLIST_PREFIX + claims.getId();
        Boolean inBlacklist = stringRedisTemplate.hasKey(blacklistKey);
        if (Boolean.TRUE.equals(inBlacklist)) {
            throw new BusinessException(ResultCode.TOKEN_INVALID, "该 Token 已失效（用户已登出）");
        }

        // 写入用户上下文
        UserContext.setUserId(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception ex) {
        UserContext.clear();
    }
}
