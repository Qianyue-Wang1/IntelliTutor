package com.intellitutor.scut.common.utils;

/**
 * 用户上下文持有器（基于 ThreadLocal）
 *
 * <p>
 * 由 {@code JwtInterceptor} 在请求预处理阶段写入当前用户 ID，
 * Controller / Service 层可通过 {@link #getUserId()} 获取。
 * 请求结束后由 {@code afterCompletion} 清理，防止内存泄漏。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
public final class UserContext {

    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();

    private UserContext() {
        // 工具类，禁止实例化
    }

    /**
     * 设置当前线程的用户 ID。
     *
     * @param userId 用户唯一标识
     */
    public static void setUserId(String userId) {
        CURRENT_USER_ID.set(userId);
    }

    /**
     * 获取当前线程的用户 ID。
     *
     * @return 用户 ID，未登录时为 {@code null}
     */
    public static String getUserId() {
        return CURRENT_USER_ID.get();
    }

    /**
     * 清除当前线程的用户上下文，防止 ThreadLocal 内存泄漏。
     */
    public static void clear() {
        CURRENT_USER_ID.remove();
    }
}
