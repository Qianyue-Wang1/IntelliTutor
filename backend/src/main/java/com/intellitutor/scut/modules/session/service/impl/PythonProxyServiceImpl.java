package com.intellitutor.scut.modules.session.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.modules.session.entity.SessionRecord;
import com.intellitutor.scut.modules.session.service.PythonProxyService;
import com.intellitutor.scut.modules.session.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Python 推理端 HTTP 代理实现。
 *
 * <p>
 * 关键设计：
 * <ul>
 * <li>{@link PythonResponseErrorHandler} 将 4xx/5xx 统一转为 BusinessException，
 * 上层不需要捕获 HttpClientErrorException。</li>
 * <li>{@link #proxyWithSessionRecovery} 在 Python 返回 404 时，
 * 自动用 state_snapshot 重建 session，然后重试一次。</li>
 * <li>RestTemplate 使用独立 ObjectMapper（不带 SNAKE_CASE），
 * DTO 上的 {@code @JsonProperty} 注解控制 JSON 键名，确保与 Python 期望一致。</li>
 * </ul>
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Slf4j
@Service
public class PythonProxyServiceImpl implements PythonProxyService {

    @Value("${intellitutor.python.inference-base-url}")
    private String pythonBaseUrl;

    private final RestTemplate restTemplate;
    private final SessionService sessionService;

    public PythonProxyServiceImpl(
            @Value("${intellitutor.python.connect-timeout-ms:5000}") int connectTimeout,
            @Value("${intellitutor.python.read-timeout-ms:720000}") int readTimeout,
            SessionService sessionService) {

        this.sessionService = sessionService;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        RestTemplate rt = new RestTemplate(factory);
        // 自定义错误处理：统一将 4xx/5xx 映射为 BusinessException
        rt.setErrorHandler(new PythonResponseErrorHandler());
        this.restTemplate = rt;
    }

    // ===================================================================
    // 公开接口实现
    // ===================================================================

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> proxy(String pythonPath, HttpMethod method, Object body) {
        String url = pythonBaseUrl + pythonPath;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<?> entity = (body != null)
                ? new HttpEntity<>(body, headers)
                : new HttpEntity<>(headers);

        log.debug("[Proxy] {} {}", method, url);
        ResponseEntity<Map> resp = restTemplate.exchange(url, method, entity, Map.class);
        return resp.getBody();
    }

    @Override
    public Map<String, Object> proxyWithSessionRecovery(
            SessionRecord session,
            String sessionType,
            String pythonPath,
            HttpMethod method,
            Object body) {

        String pythonSessionId = session.getPythonSessionId();
        if (pythonSessionId == null) {
            throw new BusinessException(ResultCode.SESSION_NOT_EXIST,
                    "Python session 尚未初始化，请先等待 init 任务完成");
        }

        String resolvedPath = pythonPath.replace("{pythonSessionId}", pythonSessionId);
        try {
            return proxy(resolvedPath, method, body);

        } catch (BusinessException ex) {
            // 仅对 Python 404（session not found）触发恢复流程
            if (ex.getResultCode() != ResultCode.SESSION_NOT_EXIST) {
                throw ex;
            }
            log.warn("[Proxy] Python session 丢失，尝试恢复 javaSessionId={} type={}",
                    session.getId(), sessionType);

            // 用 state_snapshot 重建 Python session，拿到新 pythonSessionId
            String newPythonSessionId = sessionService.recoverPythonSession(session, sessionType);

            // 用新 ID 重试一次
            String retryPath = pythonPath.replace("{pythonSessionId}", newPythonSessionId);
            Object retryBody = updateSessionIdInBody(body, newPythonSessionId);
            log.info("[Proxy] 恢复成功，重试请求 newPythonSessionId={}", newPythonSessionId);
            return proxy(retryPath, method, retryBody);
        }
    }

    // ===================================================================
    // 私有工具方法
    // ===================================================================

    /**
     * 若 body 是 Map 且含 {@code session_id} 字段，替换为新的 pythonSessionId。
     * 用于重试时修正请求体内的 session_id。
     */
    @SuppressWarnings("unchecked")
    private Object updateSessionIdInBody(Object body, String newPythonSessionId) {
        if (body instanceof Map) {
            Map<String, Object> map = new HashMap<>((Map<String, Object>) body);
            if (map.containsKey("session_id")) {
                map.put("session_id", newPythonSessionId);
            }
            return map;
        }
        return body;
    }

    // ===================================================================
    // 内部类：自定义 ResponseErrorHandler
    // ===================================================================

    /**
     * 将 RestTemplate 的 HTTP 错误响应统一映射为 BusinessException，
     * 避免 HttpClientErrorException / HttpServerErrorException 泄漏到 Controller 层。
     */
    private static class PythonResponseErrorHandler implements ResponseErrorHandler {

        @Override
        public boolean hasError(ClientHttpResponse response) throws IOException {
            return response.getStatusCode().isError();
        }

        @Override
        public void handleError(ClientHttpResponse response) throws IOException {
            int statusCode = response.getStatusCode().value();
            String body = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
            log.error("[PythonProxy] 非 2xx 响应 status={} body={}", statusCode, body);

            switch (statusCode) {
                case 404 -> throw new BusinessException(ResultCode.SESSION_NOT_EXIST,
                        "Python session 不存在（404）：" + body);
                case 400 -> throw new BusinessException(ResultCode.PARAM_ERROR,
                        "Python 参数校验失败（400）：" + body);
                case 422 -> throw new BusinessException(ResultCode.PARAM_ERROR,
                        "Python 请求体格式错误（422）：" + body);
                case 500 -> throw new BusinessException(ResultCode.REMOTE_CALL_ERROR,
                        "Python 内部错误（500）：" + body);
                default -> throw new BusinessException(ResultCode.REMOTE_CALL_ERROR,
                        "Python 返回异常 status=" + statusCode + "：" + body);
            }
        }
    }
}
