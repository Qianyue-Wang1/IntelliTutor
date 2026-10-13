package com.intellitutor.scut.modules.task.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.common.constant.MqConstants;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.modules.session.dto.req.DiscussionFeedbackReq;
import com.intellitutor.scut.modules.session.dto.req.DiscussionInitReq;
import com.intellitutor.scut.modules.session.dto.req.ExplanationInitReq;
import com.intellitutor.scut.modules.session.dto.req.StepOptionReq;
import com.intellitutor.scut.modules.session.service.SessionService;
import com.intellitutor.scut.modules.task.dto.InferenceTaskMsg;
import com.intellitutor.scut.modules.task.dto.vo.TaskStatusVO;
import com.intellitutor.scut.modules.task.entity.TaskRecord;
import com.intellitutor.scut.modules.task.mapper.TaskMapper;
import com.intellitutor.scut.modules.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 异步推理任务发布、状态管理与轮询实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskServiceImpl implements TaskService {

    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_PROCESSING = "processing";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILED = "failed";
    /** 覆盖最长 12 分钟的 Java→Python 请求，并额外保留 3 分钟防重入余量。 */
    private static final int INFERENCE_LOCK_TTL_SECONDS = 900;

    private final TaskMapper taskMapper;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SessionService sessionService;

    @Override
    public String publishExplanationInit(String sessionId, String userId, ExplanationInitReq req) {
        return doPublish(sessionId, "EXPLANATION_INIT",
                "/api/v1/explanation/init", "POST",
                MqConstants.ROUTING_KEY_EXPLAIN, req, userId, INFERENCE_LOCK_TTL_SECONDS);
    }

    @Override
    public String publishDiscussionInit(String sessionId, String userId, DiscussionInitReq req) {
        return doPublish(sessionId, "DISCUSSION_INIT",
                "/api/v1/discussion/init", "POST",
                MqConstants.ROUTING_KEY_DISCUSS, req, userId, INFERENCE_LOCK_TTL_SECONDS);
    }

    @Override
    public String publishGetStep(String javaSessionId, String pythonSessionId, String userId) {
        return doPublish(javaSessionId, "EXPLANATION_GETSTEP",
                "/api/v1/explanation/getstep/" + pythonSessionId, "GET",
                MqConstants.ROUTING_KEY_EXPLAIN, null, userId, INFERENCE_LOCK_TTL_SECONDS);
    }

    @Override
    public String publishCommitAnswer(String javaSessionId, String userId, StepOptionReq req) {
        return doPublish(javaSessionId, "EXPLANATION_COMMIT_ANSWER",
                "/api/v1/explanation/commitanswer", "POST",
                MqConstants.ROUTING_KEY_EXPLAIN, req, userId, INFERENCE_LOCK_TTL_SECONDS);
    }

    @Override
    public String publishDiscussionFeedback(String javaSessionId, String userId, DiscussionFeedbackReq req) {
        return doPublish(javaSessionId, "DISCUSSION_FEEDBACK",
                "/api/v1/discussion/feedback", "POST",
                MqConstants.ROUTING_KEY_DISCUSS, req, userId, INFERENCE_LOCK_TTL_SECONDS);
    }

    @Override
    public String publishDirectInit(String sessionId, String userId, Map<String, Object> req) {
        return doPublish(sessionId, "DIRECT_INIT", "/api/v1/direct/init", "POST",
                MqConstants.ROUTING_KEY_EXPLAIN, req, userId, INFERENCE_LOCK_TTL_SECONDS);
    }

    @Override
    public String publishDirectFollowup(String sessionId, String userId, Map<String, Object> req) {
        return doPublish(sessionId, "DIRECT_FOLLOWUP", "/api/v1/direct/followup", "POST",
                MqConstants.ROUTING_KEY_EXPLAIN, req, userId, INFERENCE_LOCK_TTL_SECONDS);
    }

    @Override
    public TaskStatusVO getTaskStatus(String taskId, String userId) {
        TaskRecord record = taskMapper.selectById(taskId);
        if (record == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "任务不存在");
        }
        // The final task result must remain readable after the run atomically
        // changes to COMPLETED. Keep strict ownership without re-opening Tutor.
        var ownedSession = sessionService.getSessionById(record.getSessionId());
        if (ownedSession == null || !userId.equals(ownedSession.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权限访问该任务");
        }

        String status = redisTemplate.opsForValue().get(MqConstants.TASK_STATUS_PREFIX + taskId);
        String resultJson = null;
        String errorMessage = record.getErrorMessage();

        if (STATUS_SUCCESS.equals(status)) {
            resultJson = redisTemplate.opsForValue().get(MqConstants.TASK_RESULT_PREFIX + taskId);
            if (resultJson == null) {
                resultJson = record.getResult();
            }
        } else if (status == null) {
            status = record.getStatus();
            if (STATUS_SUCCESS.equals(status)) {
                resultJson = record.getResult();
            }
        }

        return buildVO(record, status, resultJson, errorMessage);
    }

    @Override
    public void markTaskProcessing(String taskId) {
        int updated = taskMapper.updateStatusToProcessing(taskId);
        if (updated == 0) {
            log.warn("[Task] 标记 processing 未更新任何记录 taskId={}", taskId);
        }
        redisTemplate.opsForValue().set(
                MqConstants.TASK_STATUS_PREFIX + taskId,
                STATUS_PROCESSING,
                Duration.ofHours(2));
    }

    @Override
    public void markTaskSuccess(String taskId, String resultJson) {
        taskMapper.updateStatusToSuccess(taskId, resultJson, LocalDateTime.now());
        redisTemplate.opsForValue().set(
                MqConstants.TASK_STATUS_PREFIX + taskId,
                STATUS_SUCCESS,
                Duration.ofHours(1));
        redisTemplate.opsForValue().set(
                MqConstants.TASK_RESULT_PREFIX + taskId,
                resultJson,
                Duration.ofHours(1));
    }

    @Override
    public void markTaskFailed(String taskId, String errorMessage) {
        String safeError = errorMessage != null ? errorMessage : "推理任务失败";
        taskMapper.updateStatusToFailed(taskId, safeError, LocalDateTime.now());
        redisTemplate.opsForValue().set(
                MqConstants.TASK_STATUS_PREFIX + taskId,
                STATUS_FAILED,
                Duration.ofHours(1));
    }

    private String doPublish(String sessionId, String taskType, String pythonEndpoint,
            String httpMethod, String routingKey, Object reqBody, String userId, int lockTtlSeconds) {

        String lockKey = MqConstants.INFERENCE_LOCK_PREFIX + sessionId;
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, taskType, Duration.ofSeconds(lockTtlSeconds));
        if (!Boolean.TRUE.equals(locked)) {
            throw new BusinessException(ResultCode.SESSION_STATE_CONFLICT,
                    "该会话存在进行中的任务，请稍后再试");
        }

        TaskRecord task = new TaskRecord();
        task.setSessionId(sessionId);
        task.setTaskType(taskType);
        task.setStatus(STATUS_QUEUED);

        boolean inserted = false;
        boolean published = false;
        try {
            task.setRequestBody(reqBody != null ? objectMapper.writeValueAsString(reqBody) : null);
            taskMapper.insert(task);
            inserted = true;

            redisTemplate.opsForValue().set(
                    MqConstants.TASK_STATUS_PREFIX + task.getId(),
                    STATUS_QUEUED,
                    Duration.ofHours(2));

            InferenceTaskMsg msg = InferenceTaskMsg.builder()
                    .taskId(task.getId())
                    .sessionId(sessionId)
                    .taskType(taskType)
                    .pythonEndpoint(pythonEndpoint)
                    .httpMethod(httpMethod)
                    .requestBodyJson(task.getRequestBody())
                    .userId(userId)
                    .build();

            if (TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            rabbitTemplate.convertAndSend(MqConstants.EXCHANGE_LLM_INFERENCE, routingKey, msg);
                            log.info("[Task] 事务提交后发布 MQ 任务 taskId={} type={} sessionId={}", task.getId(), taskType, sessionId);
                        } catch (RuntimeException error) {
                            markTaskFailed(task.getId(), "MQ 投递失败：" + error.getMessage());
                            redisTemplate.delete(lockKey);
                            log.error("[Task] 事务提交后 MQ 投递失败 taskId={} err={}", task.getId(), error.getMessage(), error);
                        }
                    }

                    @Override
                    public void afterCompletion(int status) {
                        if (status != TransactionSynchronization.STATUS_COMMITTED) {
                            redisTemplate.delete(lockKey);
                            redisTemplate.delete(MqConstants.TASK_STATUS_PREFIX + task.getId());
                        }
                    }
                });
                published = true;
            } else {
                rabbitTemplate.convertAndSend(MqConstants.EXCHANGE_LLM_INFERENCE, routingKey, msg);
                published = true;
                log.info("[Task] 已发布 MQ 任务 taskId={} type={} sessionId={}", task.getId(), taskType, sessionId);
            }
            return task.getId();

        } catch (JsonProcessingException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "任务请求体序列化失败", e);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            if (inserted) {
                markTaskFailed(task.getId(), "MQ 投递失败：" + e.getMessage());
            }
            throw new BusinessException(ResultCode.MQ_ERROR, "MQ 任务投递失败：" + e.getMessage(), e);
        } finally {
            if (!published) {
                redisTemplate.delete(lockKey);
            }
        }
    }

    private TaskStatusVO buildVO(TaskRecord record, String status, String resultJson, String errorMessage) {
        Object data = null;
        if (STATUS_SUCCESS.equals(status) && resultJson != null) {
            try {
                Map<?, ?> wrapper = objectMapper.readValue(resultJson, Map.class);
                data = wrapper.get("data");
            } catch (Exception e) {
                log.warn("[Task] 解析任务结果失败 taskId={} err={}", record.getId(), e.getMessage());
            }
        }

        return TaskStatusVO.builder()
                .taskId(record.getId())
                .status(status)
                .data(data)
                .errorMessage(STATUS_FAILED.equals(status) ? errorMessage : null)
                .createdAt(record.getCreatedAt())
                .completedAt(record.getCompletedAt())
                .build();
    }
}
