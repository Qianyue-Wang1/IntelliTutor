package com.intellitutor.scut.modules.task.mq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.common.constant.MqConstants;
import com.intellitutor.scut.common.constant.InferenceLocks;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.modules.session.entity.SessionRecord;
import com.intellitutor.scut.modules.session.service.PythonProxyService;
import com.intellitutor.scut.modules.session.service.SessionService;
import com.intellitutor.scut.modules.task.dto.InferenceTaskMsg;
import com.intellitutor.scut.modules.task.service.TaskService;
import com.intellitutor.scut.modules.experiment.service.ExperimentTutorService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Consumes async inference tasks and bridges RabbitMQ to Python.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InferenceListener {

    private static final int PYTHON_SUCCESS_CODE = 200;

    private final TaskService taskService;
    private final SessionService sessionService;
    private final PythonProxyService proxyService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final ExperimentTutorService experimentTutorService;

    @RabbitListener(queues = MqConstants.QUEUE_LLM_INFERENCE, ackMode = "MANUAL")
    public void handleInference(
            @Payload InferenceTaskMsg msg,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {

        String lockKey = MqConstants.INFERENCE_LOCK_PREFIX + msg.getSessionId();
        boolean lockReleased = false;
        try {
            if (!msg.getTaskId().equals(redisTemplate.opsForValue().get(lockKey))) {
                throw new BusinessException(ResultCode.SESSION_STATE_CONFLICT,
                        "推理任务锁已失效，请重新提交任务");
            }
            taskService.markTaskProcessing(msg.getTaskId());

            Map<String, Object> pyResult = callPython(msg);
            assertPythonSuccess(pyResult);

            if (isInitTask(msg.getTaskType())) {
                bindPythonSessionId(msg, pyResult);
            }

            String resultJson = objectMapper.writeValueAsString(pyResult);
            refreshSnapshotQuietly(msg);
            experimentTutorService.onInferenceSucceeded(msg, pyResult);

            // A successful task may immediately be followed by another inference
            // request for the same session (for example commitanswer -> getstep).
            // Do not expose success while the predecessor still owns the session
            // lock, otherwise the follow-up request deterministically races with
            // this listener's finally block and is rejected as state conflict.
            InferenceLocks.release(redisTemplate, lockKey, msg.getTaskId());
            lockReleased = true;
            taskService.markTaskSuccess(msg.getTaskId(), resultJson);

            channel.basicAck(deliveryTag, false);
            log.info("[Task] Inference task succeeded taskId={} type={} sessionId={}",
                    msg.getTaskId(), msg.getTaskType(), msg.getSessionId());
        } catch (BusinessException e) {
            markFailedAndNack(msg, channel, deliveryTag, e.getMessage(), e);
        } catch (Exception e) {
            markFailedAndNack(msg, channel, deliveryTag, e.getMessage(), e);
        } finally {
            if (!lockReleased) {
                InferenceLocks.release(redisTemplate, lockKey, msg.getTaskId());
            }
        }
    }

    private Map<String, Object> callPython(InferenceTaskMsg msg) throws IOException {
        Object body = null;
        if (msg.getRequestBodyJson() != null && !msg.getRequestBodyJson().isBlank()) {
            body = objectMapper.readValue(msg.getRequestBodyJson(), new TypeReference<Map<String, Object>>() {
            });
        }

        HttpMethod method = HttpMethod.valueOf(msg.getHttpMethod().toUpperCase());
        if (isInitTask(msg.getTaskType())) {
            return proxyService.proxy(msg.getPythonEndpoint(), method, body);
        }

        SessionRecord session = sessionService.getSessionById(msg.getSessionId());
        if (session == null) {
            throw new BusinessException(ResultCode.SESSION_NOT_EXIST, "Java session not found");
        }

        return proxyService.proxyWithSessionRecovery(
                session,
                sessionType(msg.getTaskType()),
                pythonEndpointTemplate(msg.getTaskType()),
                method,
                body);
    }

    private void assertPythonSuccess(Map<String, Object> response) {
        Object code = response != null ? response.get("code") : null;
        if (code instanceof Number number && number.intValue() == PYTHON_SUCCESS_CODE) {
            return;
        }
        if (String.valueOf(PYTHON_SUCCESS_CODE).equals(String.valueOf(code))) {
            return;
        }

        String message = response != null && response.get("message") != null
                ? String.valueOf(response.get("message"))
                : "Python inference returned non-success response";
        throw new BusinessException(ResultCode.INFERENCE_FAILED, message);
    }

    @SuppressWarnings("unchecked")
    private void bindPythonSessionId(InferenceTaskMsg msg, Map<String, Object> response) {
        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            throw new BusinessException(ResultCode.INFERENCE_FAILED, "Python init response missing data");
        }

        Object pythonSessionId = dataMap.get("session_id");
        if (pythonSessionId == null) {
            pythonSessionId = dataMap.get("sessionId");
        }
        if (pythonSessionId == null || String.valueOf(pythonSessionId).isBlank()) {
            throw new BusinessException(ResultCode.INFERENCE_FAILED, "Python init response missing session_id");
        }

        sessionService.bindPythonSessionId(msg.getSessionId(), String.valueOf(pythonSessionId));
    }

    private void refreshSnapshotQuietly(InferenceTaskMsg msg) {
        try {
            SessionRecord session = sessionService.getSessionById(msg.getSessionId());
            if (session == null || session.getPythonSessionId() == null || session.getPythonSessionId().isBlank()) {
                return;
            }

            Map<String, Object> state = proxyService.proxyWithSessionRecovery(
                    session,
                    sessionType(msg.getTaskType()),
                    sessionStatePath(msg.getTaskType()),
                    HttpMethod.GET,
                    null);
            Object data = state != null ? state.get("data") : null;
            if (data != null) {
                sessionService.updateStateSnapshot(msg.getSessionId(), objectMapper.writeValueAsString(data));
            }
        } catch (Exception e) {
            log.warn("[Task] Refresh state snapshot failed taskId={} sessionId={} err={}",
                    msg.getTaskId(), msg.getSessionId(), e.getMessage());
        }
    }

    private String sessionStatePath(String taskType) {
        if (taskType != null && taskType.startsWith("DIRECT")) {
            return "/api/v1/direct/sessionstate/{pythonSessionId}";
        }
        if (taskType != null && taskType.startsWith("DISCUSSION")) {
            return "/api/v1/discussion/sessionstate/{pythonSessionId}";
        }
        return "/api/v1/explanation/sessionstate/{pythonSessionId}";
    }

    private String sessionType(String taskType) {
        if (taskType != null && taskType.startsWith("DIRECT")) {
            return "direct";
        }
        if (taskType != null && taskType.startsWith("DISCUSSION")) {
            return "discussion";
        }
        return "explanation";
    }

    private String pythonEndpointTemplate(String taskType) {
        if ("EXPLANATION_GETSTEP".equals(taskType)) {
            return "/api/v1/explanation/getstep/{pythonSessionId}";
        }
        if ("EXPLANATION_COMMIT_ANSWER".equals(taskType)) {
            return "/api/v1/explanation/commitanswer";
        }
        if ("DISCUSSION_FEEDBACK".equals(taskType)) {
            return "/api/v1/discussion/feedback";
        }
        if ("DIRECT_FOLLOWUP".equals(taskType)) {
            return "/api/v1/direct/followup";
        }
        throw new BusinessException(ResultCode.INFERENCE_FAILED, "Unsupported task type: " + taskType);
    }

    private boolean isInitTask(String taskType) {
        return "EXPLANATION_INIT".equals(taskType) || "DISCUSSION_INIT".equals(taskType) || "DIRECT_INIT".equals(taskType);
    }

    private void markFailedAndNack(
            InferenceTaskMsg msg,
            Channel channel,
            long deliveryTag,
            String errorMessage,
            Exception cause) {

        String safeError = errorMessage != null && !errorMessage.isBlank()
                ? errorMessage
                : "Inference task failed";
        try {
            taskService.markTaskFailed(msg.getTaskId(), safeError);
        } catch (Exception e) {
            log.error("[Task] Mark task failed error taskId={} err={}", msg.getTaskId(), e.getMessage(), e);
        }

        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException e) {
            log.error("[Task] Nack message failed taskId={} err={}", msg.getTaskId(), e.getMessage(), e);
        }

        log.error("[Task] Inference task failed taskId={} type={} sessionId={} err={}",
                msg.getTaskId(), msg.getTaskType(), msg.getSessionId(), safeError, cause);
    }
}
