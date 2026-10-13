package com.intellitutor.scut.modules.session.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.Result;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.common.utils.UserContext;
import com.intellitutor.scut.config.FeatureProperties;
import com.intellitutor.scut.modules.experiment.service.ExperimentAccessService;
import com.intellitutor.scut.modules.experiment.service.ExperimentTutorService;
import com.intellitutor.scut.modules.session.dto.req.DiscussionFeedbackReq;
import com.intellitutor.scut.modules.session.dto.req.DiscussionInitReq;
import com.intellitutor.scut.modules.session.dto.req.DiscussionUpdateStateReq;
import com.intellitutor.scut.modules.session.dto.vo.TaskAcceptedVO;
import com.intellitutor.scut.modules.session.entity.SessionRecord;
import com.intellitutor.scut.modules.session.service.PythonProxyService;
import com.intellitutor.scut.modules.session.service.SessionService;
import com.intellitutor.scut.modules.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.List;

/**
 * 讨论会话（Discussion）接口 — Phase 2 同步部分。
 *
 * <p>
 * <b>Phase 2 包含的接口（同步代理，无 MQ）：</b>
 * <ul>
 * <li>{@code GET   /v1/discussion/sessionstate/{sessionId}} —
 * 获取当前讨论状态并保存快照</li>
 * <li>{@code POST  /v1/discussion/updatestate} — 持久化讨论会话状态</li>
 * <li>{@code GET   /v1/discussion/knowledgetracing/{sessionId}} — 获取知识追踪结果</li>
 * </ul>
 *
 * <p>
 * <b>Phase 3 将新增（MQ 异步）：</b> init, feedback。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/v1/discussion")
public class DiscussionController {

    private static final String SESSION_TYPE = "discussion";

    private final SessionService sessionService;
    private final PythonProxyService proxyService;
    private final ObjectMapper objectMapper;
    private final TaskService taskService;
    private final FeatureProperties features;
    private final ExperimentAccessService experimentAccessService;
    private final ExperimentTutorService experimentTutorService;

    @org.springframework.beans.factory.annotation.Autowired
    public DiscussionController(SessionService sessionService, PythonProxyService proxyService,
                                ObjectMapper objectMapper, TaskService taskService, FeatureProperties features,
                                ExperimentAccessService experimentAccessService, ExperimentTutorService experimentTutorService) {
        this.sessionService = sessionService; this.proxyService = proxyService; this.objectMapper = objectMapper;
        this.taskService = taskService; this.features = features; this.experimentAccessService = experimentAccessService; this.experimentTutorService = experimentTutorService;
    }

    public DiscussionController(SessionService sessionService, PythonProxyService proxyService,
                                ObjectMapper objectMapper, TaskService taskService, FeatureProperties features) {
        this(sessionService, proxyService, objectMapper, taskService, features, null, null);
    }

    // ===================================================================
    // Phase 3：异步 Task/MQ 接口
    // ===================================================================

    @PostMapping("/init")
    public Result<TaskAcceptedVO> initDiscussion(@Validated @RequestBody DiscussionInitReq req) {
        validateImageCapability(req.getImages(), req.getImageCode());
        String userId = UserContext.getUserId();
        if (experimentAccessService != null) experimentAccessService.rejectLegacyTutorForExperimentStudent(userId);
        req.setUserId(userId);
        String sessionId = sessionService.createSessionRecord(userId, SESSION_TYPE, req.getProcessedQuestion());
        try {
            String taskId = taskService.publishDiscussionInit(sessionId, userId, req);
            return accepted(taskId, sessionId);
        } catch (RuntimeException e) {
            deleteCreatedSessionQuietly(sessionId, userId);
            throw e;
        }
    }

    @PostMapping("/feedback")
    public Result<TaskAcceptedVO> feedback(@RequestBody DiscussionFeedbackReq req) {
        String userId = UserContext.getUserId();
        String sessionId = req.getSessionId();
        ensureSessionIdPresent(sessionId);
        SessionRecord session = sessionService.getAndValidateSession(sessionId, userId, SESSION_TYPE);
        ensurePythonSessionInitialized(session);
        if (experimentTutorService != null) experimentTutorService.requireStructuredInteraction(userId, sessionId);

        req.setUserId(userId);
        DiscussionFeedbackReq pythonReq = req.withSessionId(session.getPythonSessionId());
        String taskId = taskService.publishDiscussionFeedback(sessionId, userId, pythonReq);
        return accepted(taskId, sessionId);
    }

    // ===================================================================
    // Phase 2：同步代理接口
    // ===================================================================

    /**
     * 获取当前讨论会话的完整状态，并持久化为 state_snapshot（用于 Python 重启恢复）。
     *
     * @param sessionId Java sessionId
     * @return Python 端讨论状态响应
     */
    @GetMapping("/sessionstate/{sessionId}")
    public Map<String, Object> getSessionState(@PathVariable String sessionId) {
        String userId = UserContext.getUserId();
        SessionRecord session = sessionService.getAndValidateSession(sessionId, userId, SESSION_TYPE);

        Map<String, Object> result = proxyService.proxyWithSessionRecovery(
                session,
                SESSION_TYPE,
                "/api/v1/discussion/sessionstate/{pythonSessionId}",
                HttpMethod.GET,
                null);

        // 将 Python 返回的 data 字段保存为 state_snapshot
        saveSnapshotQuietly(sessionId, result);
        sessionService.touchSession(sessionId);
        return SessionResponseMapper.exposeJavaSessionId(result, sessionId);
    }

    /**
     * 更新讨论会话状态（discussionTree、messagesHistory、knowledgePoints 等）。
     *
     * <p>
     * 请求体中的 {@code session_id} 为 Java sessionId，代理前自动替换为 Python sessionId。
     *
     * @param req 讨论状态更新请求
     * @return Python 端响应原样透传
     */
    @PostMapping("/updatestate")
    public Map<String, Object> updateState(@RequestBody DiscussionUpdateStateReq req) {
        String userId = UserContext.getUserId();
        String javaSessionId = req.getSessionId();
        SessionRecord session = sessionService.getAndValidateSession(javaSessionId, userId, SESSION_TYPE);

        // 将 DTO 转为 Map，替换 session_id 为 pythonSessionId
        Map<String, Object> bodyMap = objectMapper.convertValue(req, new TypeReference<>() {
        });
        bodyMap.put("session_id", session.getPythonSessionId());

        Map<String, Object> result = proxyService.proxyWithSessionRecovery(
                session, SESSION_TYPE, "/api/v1/discussion/updatestate", HttpMethod.POST, bodyMap);

        if (isPythonSuccess(result)) {
            if (result.get("data") != null) {
                saveSnapshotQuietly(javaSessionId, result);
            } else {
                refreshSnapshotFromPython(javaSessionId);
            }
        }
        sessionService.touchSession(javaSessionId);
        return SessionResponseMapper.exposeJavaSessionId(result, javaSessionId);
    }

    /**
     * 获取本次讨论会话对应的知识追踪结果。
     *
     * @param sessionId Java sessionId
     * @return 知识追踪数据
     */
    @GetMapping("/knowledgetracing/{sessionId}")
    public Map<String, Object> getKnowledgeTracing(@PathVariable String sessionId) {
        String userId = UserContext.getUserId();
        SessionRecord session = sessionService.getAndValidateSession(sessionId, userId, SESSION_TYPE);

        Map<String, Object> result = proxyService.proxyWithSessionRecovery(
                session,
                SESSION_TYPE,
                "/api/v1/discussion/knowledgetracing/{pythonSessionId}",
                HttpMethod.GET,
                null);

        sessionService.touchSession(sessionId);
        return result;
    }

    // ===================================================================
    // 私有工具
    // ===================================================================

    private Result<TaskAcceptedVO> accepted(String taskId, String sessionId) {
        return Result.accepted(TaskAcceptedVO.builder()
                .taskId(taskId)
                .sessionId(sessionId)
                .build());
    }

    private void validateImageCapability(List<?> images, List<?> imageCode) {
        if ((images != null && !images.isEmpty()) || (imageCode != null && !imageCode.isEmpty())) {
            features.requireImageInputEnabled();
        }
    }

    private void ensureSessionIdPresent(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "session_id 不能为空");
        }
    }

    private void ensurePythonSessionInitialized(SessionRecord session) {
        if (session.getPythonSessionId() == null || session.getPythonSessionId().isBlank()) {
            throw new BusinessException(ResultCode.SESSION_NOT_EXIST,
                    "Python session 尚未初始化，请先等待 init 任务完成");
        }
    }

    private void deleteCreatedSessionQuietly(String sessionId, String userId) {
        try {
            sessionService.deleteSession(sessionId, userId);
        } catch (Exception e) {
            log.warn("[Discussion] 清理 init 投递失败会话失败 sessionId={} err={}", sessionId, e.getMessage());
        }
    }

    /**
     * 将 Python 响应中的 {@code data} 字段序列化为 JSON 字符串，保存为 state_snapshot。
     * 序列化失败时仅记录警告，不影响主流程。
     */
    private void saveSnapshotQuietly(String sessionId, Map<String, Object> response) {
        try {
            Object data = response.get("data");
            if (data != null) {
                String snapshotJson = objectMapper.writeValueAsString(data);
                sessionService.updateStateSnapshot(sessionId, snapshotJson);
            }
        } catch (Exception e) {
            log.warn("[Discussion] 保存 state_snapshot 失败 sessionId={} err={}", sessionId, e.getMessage());
        }
    }

    private void refreshSnapshotFromPython(String sessionId) {
        try {
            SessionRecord latestSession = sessionService.getSessionById(sessionId);
            if (latestSession == null) {
                log.warn("[Discussion] 刷新 state_snapshot 失败，session 不存在 sessionId={}", sessionId);
                return;
            }
            Map<String, Object> state = proxyService.proxyWithSessionRecovery(
                    latestSession,
                    SESSION_TYPE,
                    "/api/v1/discussion/sessionstate/{pythonSessionId}",
                    HttpMethod.GET,
                    null);
            saveSnapshotQuietly(sessionId, state);
        } catch (Exception e) {
            log.warn("[Discussion] 刷新完整 state_snapshot 失败 sessionId={} err={}", sessionId, e.getMessage());
        }
    }

    private boolean isPythonSuccess(Map<String, Object> response) {
        Object code = response != null ? response.get("code") : null;
        if (code instanceof Number number) {
            return number.intValue() == 200;
        }
        return "200".equals(String.valueOf(code));
    }
}
