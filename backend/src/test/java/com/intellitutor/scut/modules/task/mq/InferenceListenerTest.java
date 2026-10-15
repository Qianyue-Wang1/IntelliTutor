package com.intellitutor.scut.modules.task.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.common.constant.MqConstants;
import com.intellitutor.scut.modules.experiment.service.ExperimentTutorService;
import com.intellitutor.scut.modules.session.service.PythonProxyService;
import com.intellitutor.scut.modules.session.service.SessionService;
import com.intellitutor.scut.modules.task.dto.InferenceTaskMsg;
import com.intellitutor.scut.modules.task.service.TaskService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpMethod;

import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InferenceListenerTest {

    @Test
    void releasesSessionLockBeforePublishingSuccessfulTaskStatus() throws Exception {
        TaskService taskService = mock(TaskService.class);
        SessionService sessionService = mock(SessionService.class);
        PythonProxyService proxyService = mock(PythonProxyService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(MqConstants.INFERENCE_LOCK_PREFIX + "session-1")).thenReturn("task-1");
        ExperimentTutorService experimentTutorService = mock(ExperimentTutorService.class);
        Channel channel = mock(Channel.class);
        InferenceListener listener = new InferenceListener(
                taskService,
                sessionService,
                proxyService,
                new ObjectMapper(),
                redisTemplate,
                experimentTutorService);

        Map<String, Object> response = Map.of(
                "code", 200,
                "data", Map.of("session_id", "python-session"));
        when(proxyService.proxy("/api/v1/explanation/init", HttpMethod.POST, null))
                .thenReturn(response);

        InferenceTaskMsg message = InferenceTaskMsg.builder()
                .taskId("task-1")
                .sessionId("session-1")
                .taskType("EXPLANATION_INIT")
                .pythonEndpoint("/api/v1/explanation/init")
                .httpMethod("POST")
                .userId("student-1")
                .build();

        listener.handleInference(message, channel, 42L);

        String lockKey = MqConstants.INFERENCE_LOCK_PREFIX + "session-1";
        InOrder completionOrder = inOrder(redisTemplate, taskService, channel);
        completionOrder.verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(lockKey)), eq("task-1"));
        completionOrder.verify(taskService).markTaskSuccess(anyString(), anyString());
        completionOrder.verify(channel).basicAck(42L, false);
        verify(redisTemplate, times(1)).execute(any(RedisScript.class), eq(List.of(lockKey)), eq("task-1"));
        verify(redisTemplate, never()).delete(lockKey);
    }
    @Test
    @SuppressWarnings("unchecked")
    void differentSessionsCanInferConcurrently() throws Exception {
        TaskService tasks = mock(TaskService.class);
        SessionService sessions = mock(SessionService.class);
        PythonProxyService proxy = mock(PythonProxyService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(MqConstants.INFERENCE_LOCK_PREFIX + "a")).thenReturn("task-a");
        when(values.get(MqConstants.INFERENCE_LOCK_PREFIX + "b")).thenReturn("task-b");
        CountDownLatch entered = new CountDownLatch(2);
        when(proxy.proxy("/api/v1/direct/init", HttpMethod.POST, null)).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(entered.await(3, TimeUnit.SECONDS), "Requests must overlap");
            return Map.of("code", 200, "data", Map.of("session_id", "python-session"));
        });
        InferenceListener listener = new InferenceListener(tasks, sessions, proxy, new ObjectMapper(),
                redis, mock(ExperimentTutorService.class));
        Channel channelA = mock(Channel.class);
        Channel channelB = mock(Channel.class);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> listener.handleInference(message("a"), channelA, 1L));
            var b = executor.submit(() -> listener.handleInference(message("b"), channelB, 2L));
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
        }
        verify(tasks).markTaskSuccess(eq("task-a"), anyString());
        verify(tasks).markTaskSuccess(eq("task-b"), anyString());
        verify(channelA).basicAck(1L, false);
        verify(channelB).basicAck(2L, false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void expiredTaskNeverCallsPythonOrUnconditionallyDeletesSuccessorsLock() throws Exception {
        TaskService tasks = mock(TaskService.class);
        PythonProxyService proxy = mock(PythonProxyService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        String key = MqConstants.INFERENCE_LOCK_PREFIX + "a";
        when(values.get(key)).thenReturn("new-task");
        Channel channel = mock(Channel.class);
        new InferenceListener(tasks, mock(SessionService.class), proxy, new ObjectMapper(), redis,
                mock(ExperimentTutorService.class)).handleInference(message("a"), channel, 3L);
        verify(proxy, never()).proxy(anyString(), any(), any());
        verify(redis, never()).delete(key);
        verify(redis).execute(any(RedisScript.class), eq(List.of(key)), eq("task-a"));
        verify(tasks).markTaskFailed(eq("task-a"), anyString());
    }

    private static InferenceTaskMsg message(String sessionId) {
        return InferenceTaskMsg.builder().taskId("task-" + sessionId).sessionId(sessionId)
                .taskType("DIRECT_INIT").pythonEndpoint("/api/v1/direct/init")
                .httpMethod("POST").userId("student-" + sessionId).build();
    }

}
