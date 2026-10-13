package com.intellitutor.scut.modules.session.service;

import org.springframework.http.HttpMethod;

import java.util.Map;

/**
 * Python 推理端同步 HTTP 代理服务。
 *
 * <p>
 * 封装 Java → Python 的 RestTemplate 调用，提供统一的错误处理（4xx/5xx → BusinessException）
 * 和会话恢复能力（Python 404 → 重建 in-memory session → 自动重试）。
 *
 * <p>
 * <b>线程安全</b>：RestTemplate 线程安全，实现类为单例。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
public interface PythonProxyService {

    /**
     * 基础代理：将请求转发到 Python 端，非 2xx 响应统一包装为 BusinessException。
     *
     * @param pythonPath Python 端路径（已含完整路径变量，无占位符）
     * @param method     HTTP 方法
     * @param body       请求体（GET 请求传 null）
     * @return Python 响应 JSON 解析后的 Map
     * @throws com.intellitutor.scut.common.exception.BusinessException 非 2xx 时抛出
     */
    Map<String, Object> proxy(String pythonPath, HttpMethod method, Object body);

    /**
     * 带 Session 恢复的代理。
     *
     * <p>
     * 当 Python 返回 404（session not found）时：
     * <ol>
     * <li>从 DB 读取 state_snapshot</li>
     * <li>POST /api/v1/{type}/restoresession 重建 Python 侧 session</li>
     * <li>更新 DB / Redis 中的 python_session_id</li>
     * <li>用新 pythonSessionId 自动重试原请求</li>
     * </ol>
     *
     * @param session     Java SessionRecord（含 stateSnapshot 和 pythonSessionId）
     * @param sessionType "explanation" | "discussion"
     * @param pythonPath  Python 路径模板，含 {@code {pythonSessionId}} 占位符
     * @param method      HTTP 方法
     * @param body        请求体（GET 传 null；若含 session_id 字段，会自动替换为新 ID）
     * @return Python 响应 JSON 解析后的 Map
     */
    Map<String, Object> proxyWithSessionRecovery(
            com.intellitutor.scut.modules.session.entity.SessionRecord session,
            String sessionType,
            String pythonPath,
            HttpMethod method,
            Object body);
}
