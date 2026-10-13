package com.intellitutor.scut.common.aop;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Objects;

/**
 * Controller 层请求/响应统一日志切面
 *
 * <p>
 * 自动拦截所有 {@code @RestController} 方法，记录以下信息：
 * <ul>
 * <li>请求入参：HTTP 方法、URI、客户端 IP、Controller#方法名、参数列表</li>
 * <li>响应出参：返回值（{@code toString()}）及执行耗时（ms）</li>
 * <li>异常出参：异常类型及描述（不含堆栈，由
 * {@link com.intellitutor.scut.common.exception.GlobalExceptionHandler}
 * 负责详细记录）</li>
 * </ul>
 *
 * <p>
 * <b>安全注意：</b>参数日志可能包含敏感字段（密码、Token），生产环境需配合脱敏工具
 * 或通过 {@code application.yml} 将本切面日志级别调整为 {@code INFO/OFF}。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Slf4j
@Aspect
@Component
public class LogAspect {

    /**
     * 切入点：{@code com.intellitutor.scut.modules} 包下所有 Controller 类的全部公共方法。
     */
    @Pointcut("execution(public * com.intellitutor.scut.modules..controller.*.*(..))")
    public void controllerPointcut() {
        // 切入点定义
    }

    /**
     * 环绕通知：记录请求上下文、执行耗时及响应结果。
     *
     * @param joinPoint 方法连接点
     * @return 目标方法的返回值（透明传递，不修改）
     * @throws Throwable 透明传递目标方法抛出的异常
     */
    @Around("controllerPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {

        // 获取 HttpServletRequest（不持有引用，降低内存压力）
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = Objects.requireNonNull(attributes, "ServletRequestAttributes must not be null")
                .getRequest();

        String httpMethod = request.getMethod();
        String uri = request.getRequestURI();
        String clientIp = resolveClientIp(request);
        String controllerMethod = joinPoint.getSignature().toShortString();
        // Never stringify controller arguments: authentication, password reset and
        // assessment payloads contain credentials or research answers.
        log.debug("[REQUEST] {} {} | ip={} | method={}",
                httpMethod, uri, clientIp, controllerMethod);

        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            long elapsed = System.currentTimeMillis() - start;
            // Results can contain access tokens and one-time passwords.
            log.debug("[RESPONSE] method={} | elapsed={}ms", controllerMethod, elapsed);
            return result;
        } catch (Throwable ex) {
            long elapsed = System.currentTimeMillis() - start;
            log.debug("[EXCEPTION] method={} | elapsed={}ms | exception={}:{}",
                    controllerMethod, elapsed, ex.getClass().getSimpleName(), ex.getMessage());
            throw ex;
        }
    }

    /**
     * 解析客户端真实 IP，兼容 Nginx/CDN 反向代理场景下的 {@code X-Forwarded-For} 头。
     *
     * @param request HTTP 请求
     * @return 客户端 IP 字符串
     */
    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank() && !"unknown".equalsIgnoreCase(xff)) {
            // X-Forwarded-For 可能包含多个 IP，第一个为客户端真实 IP
            return xff.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank() && !"unknown".equalsIgnoreCase(xRealIp)) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }
}
