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
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
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
        completionOrder.verify(redisTemplate).delete(lockKey);
        completionOrder.verify(taskService).markTaskSuccess(anyString(), anyString());
        completionOrder.verify(channel).basicAck(42L, false);
        verify(redisTemplate, times(1)).delete(lockKey);
    }
}
