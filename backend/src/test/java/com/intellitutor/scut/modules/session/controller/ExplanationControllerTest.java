package com.intellitutor.scut.modules.session.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.common.utils.UserContext;
import com.intellitutor.scut.config.FeatureProperties;
import com.intellitutor.scut.modules.session.dto.req.AdvanceExplanationReq;
import com.intellitutor.scut.modules.session.entity.SessionRecord;
import com.intellitutor.scut.modules.session.service.PythonProxyService;
import com.intellitutor.scut.modules.session.service.SessionService;
import com.intellitutor.scut.modules.task.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExplanationControllerTest {

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    @Test
    void advanceReplacesJavaSessionIdAndRefreshesSnapshot() {
        SessionService sessions = mock(SessionService.class);
        PythonProxyService proxy = mock(PythonProxyService.class);
        SessionRecord session = new SessionRecord();
        session.setId("java-1");
        session.setUserId("student-1");
        session.setPythonSessionId("python-1");
        UserContext.setUserId("student-1");

        when(sessions.getAndValidateSession("java-1", "student-1", "explanation")).thenReturn(session);
        when(sessions.getSessionById("java-1")).thenReturn(session);
        when(proxy.proxyWithSessionRecovery(
                eq(session), eq("explanation"), eq("/api/v1/explanation/advance"),
                eq(HttpMethod.POST), any()))
                .thenReturn(Map.of("code", 200, "data", Map.of("session_id", "python-1", "current_step", 2)));
        when(proxy.proxyWithSessionRecovery(
                eq(session), eq("explanation"), eq("/api/v1/explanation/sessionstate/{pythonSessionId}"),
                eq(HttpMethod.GET), eq(null)))
                .thenReturn(Map.of("code", 200, "data", Map.of("session_id", "python-1", "current_step", 2)));

        ExplanationController controller = new ExplanationController(
                sessions, proxy, new ObjectMapper(), mock(TaskService.class), new FeatureProperties());
        AdvanceExplanationReq request = new AdvanceExplanationReq();
        request.setSessionId("java-1");

        Map<String, Object> response = controller.advance(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertEquals("java-1", data.get("session_id"));
        verify(sessions).touchSession("java-1");
        verify(sessions).updateStateSnapshot(eq("java-1"), any());
    }
}
