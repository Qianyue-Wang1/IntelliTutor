package com.intellitutor.scut.modules.session.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.common.constant.MqConstants;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.modules.session.dto.vo.SessionListItemVO;
import com.intellitutor.scut.modules.session.entity.SessionRecord;
import com.intellitutor.scut.modules.session.mapper.SessionMapper;
import com.intellitutor.scut.modules.session.service.SessionService;
import com.intellitutor.scut.modules.experiment.service.ExperimentAccessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会话管理服务实现。
 *
 * <p>
 * 注意：{@code recoverPythonSession} 直接使用内部 {@code recoveryRestTemplate}（非
 * PythonProxyService），
 * 以避免 PythonProxyService → SessionService 的循环依赖。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Slf4j
@Service
public class SessionServiceImpl implements SessionService {

    private final SessionMapper sessionMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ExperimentAccessService experimentAccessService;

    /** 用于 recoverPythonSession 的专用 RestTemplate，避免与 PythonProxyServiceImpl 循环依赖 */
    private final RestTemplate recoveryRestTemplate;

    @Value("${intellitutor.python.inference-base-url}")
    private String pythonBaseUrl;

    @Autowired
    public SessionServiceImpl(
            SessionMapper sessionMapper,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            ExperimentAccessService experimentAccessService,
            @Value("${intellitutor.python.connect-timeout-ms:5000}") int connectTimeout) {
        this.sessionMapper = sessionMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.experimentAccessService = experimentAccessService;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(30_000);
        this.recoveryRestTemplate = new RestTemplate(factory);
    }

    // ===================================================================
    // 接口实现
    // ===================================================================

    @Override
    public List<SessionListItemVO> listSessions(String userId) {
        List<SessionRecord> records = sessionMapper.selectList(
                new LambdaQueryWrapper<SessionRecord>()
                        .eq(SessionRecord::getUserId, userId)
                        .orderByDesc(SessionRecord::getCreatedAt));

        return records.stream()
                .map(r -> SessionListItemVO.builder()
                        .id(r.getId())
                        .question(r.getQuestion())
                        .sessionType(r.getSessionType())
                        .status(r.getStatus())
                        .createdAt(r.getCreatedAt())
                        .lastAccessedAt(r.getLastAccessedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteSession(String sessionId, String userId) {
        SessionRecord session = getAndValidateSession(sessionId, userId);

        // 1. DB 逻辑删除
        sessionMapper.deleteById(sessionId);

        // 2. 清理 Redis 缓存
        redisTemplate.delete(MqConstants.SESSION_META_PREFIX + sessionId);

        // 3. 尝试通知 Python 释放 in-memory session（失败不影响主流程）
        if (session.getPythonSessionId() != null) {
            try {
                String url = pythonBaseUrl + "/api/v1/deletesession/" + session.getPythonSessionId();
                recoveryRestTemplate.delete(url);
                log.debug("[Session] Python session 已释放 pythonSessionId={}", session.getPythonSessionId());
            } catch (Exception e) {
                log.warn("[Session] 通知 Python 释放 session 失败（不影响删除），err={}", e.getMessage());
            }
        }
    }

    @Override
    public String createSessionRecord(String userId, String sessionType, String question) {
        SessionRecord record = new SessionRecord();
        record.setUserId(userId);
        record.setSessionType(sessionType);
        record.setQuestion(question);
        record.setStatus("active");
        sessionMapper.insert(record);

        log.debug("[Session] 创建会话记录 sessionId={} type={}", record.getId(), sessionType);
        return record.getId();
    }

    @Override
    public String createExperimentSession(String userId, String sessionType, String question, String tutorRunId) {
        SessionRecord record = new SessionRecord();
        record.setUserId(userId);
        record.setSessionType(sessionType);
        record.setQuestion(question);
        record.setStatus("active");
        record.setExperimentTutorRunId(tutorRunId);
        sessionMapper.insert(record);
        return record.getId();
    }

    @Override
    public void bindPythonSessionId(String sessionId, String pythonSessionId) {
        sessionMapper.updatePythonSessionId(sessionId, pythonSessionId);

        // 更新 Redis 元数据缓存（失效后重新写入）
        redisTemplate.delete(MqConstants.SESSION_META_PREFIX + sessionId);
        log.debug("[Session] pythonSessionId 已绑定 javaSession={} pySession={}",
                sessionId, pythonSessionId);
    }

    @Override
    public void updateStateSnapshot(String sessionId, String snapshotJson) {
        int updated = sessionMapper.updateStateSnapshot(sessionId, snapshotJson);
        if (updated > 0) {
            // 快照更新后使 Redis 元数据缓存失效，下次读取从 DB 获取最新数据
            redisTemplate.delete(MqConstants.SESSION_META_PREFIX + sessionId);
            log.debug("[Session] state_snapshot 已更新 sessionId={}", sessionId);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public String recoverPythonSession(SessionRecord session, String sessionType) {
        String snapshot = session.getStateSnapshot();
        if (snapshot == null || snapshot.isBlank()) {
            throw new BusinessException(ResultCode.SESSION_NOT_EXIST,
                    "无法恢复 Python session：state_snapshot 为空，请重新创建会话");
        }

        try {
            // 将 snapshot JSON 字符串解析为 Map
            Map<String, Object> snapshotMap = objectMapper.readValue(
                    snapshot, new TypeReference<>() {
                    });

            // 构造恢复请求体
            Map<String, Object> reqBody = new HashMap<>();
            reqBody.put("java_session_id", session.getId());
            reqBody.put("snapshot", snapshotMap);
            reqBody.put("user_id", session.getUserId());

            String restoreUrl = pythonBaseUrl
                    + "/api/v1/" + sessionType + "/restoresession";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(reqBody, headers);

            ResponseEntity<Map> resp = recoveryRestTemplate.postForEntity(
                    restoreUrl, entity, Map.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                Object data = resp.getBody().get("data");
                if (data instanceof Map) {
                    Object newPythonSessionId = ((Map<?, ?>) data).get("session_id");
                    if (newPythonSessionId != null) {
                        // 回填新 pythonSessionId
                        bindPythonSessionId(session.getId(), newPythonSessionId.toString());
                        log.info("[Session] 会话已恢复 javaSession={} newPySession={}",
                                session.getId(), newPythonSessionId);
                        return newPythonSessionId.toString();
                    }
                }
            }
            throw new BusinessException(ResultCode.REMOTE_CALL_ERROR,
                    "Python restoresession 返回格式异常");

        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("[Session] Python session 恢复失败 sessionId={}", session.getId(), e);
            throw new BusinessException(ResultCode.REMOTE_CALL_ERROR,
                    "Python session 恢复失败：" + e.getMessage());
        }
    }

    @Override
    public void touchSession(String sessionId) {
        sessionMapper.updateLastAccessedAt(sessionId, LocalDateTime.now());
    }

    @Override
    public SessionRecord getAndValidateSession(String sessionId, String userId) {
        SessionRecord session = getOwnedSession(sessionId, userId);
        experimentAccessService.requireSessionAccess(userId, sessionId, null);
        return session;
    }

    private SessionRecord getOwnedSession(String sessionId, String userId) {
        SessionRecord session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.SESSION_NOT_EXIST);
        }
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "无权限访问该会话");
        }
        return session;
    }

    @Override
    public SessionRecord getAndValidateSession(String sessionId, String userId, String expectedType) {
        SessionRecord session = getOwnedSession(sessionId, userId);
        if (expectedType != null && !expectedType.equals(session.getSessionType())) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "会话类型不匹配，期望 " + expectedType + "，实际 " + session.getSessionType());
        }
        experimentAccessService.requireSessionAccess(userId, sessionId, expectedType);
        return session;
    }

    @Override
    public SessionRecord getSessionById(String sessionId) {
        return sessionMapper.selectById(sessionId);
    }
}
