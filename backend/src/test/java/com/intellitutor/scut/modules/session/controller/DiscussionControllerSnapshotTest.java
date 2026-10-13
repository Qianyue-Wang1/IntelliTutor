package com.intellitutor.scut.modules.session.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.common.utils.UserContext;
import com.intellitutor.scut.config.FeatureProperties;
import com.intellitutor.scut.modules.session.entity.SessionRecord;
import com.intellitutor.scut.modules.session.service.PythonProxyService;
import com.intellitutor.scut.modules.session.service.SessionService;
import com.intellitutor.scut.modules.task.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscussionControllerSnapshotTest {

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    @Test
    void sessionStatePersistsNestedDiscussionPlanWithoutFiltering() throws Exception {
        SessionService sessions = mock(SessionService.class);
        PythonProxyService proxy = mock(PythonProxyService.class);
        SessionRecord session = new SessionRecord();
        session.setId("java-1");
        session.setUserId("student-1");
        session.setPythonSessionId("python-1");
        UserContext.setUserId("student-1");

        Map<String, Object> plan = Map.of(
                "schema_version", 1,
                "revision", 3,
                "active_node_id", "n3",
                "root_node_ids", List.of("n1"),
                "nodes", List.of(Map.of("id", "n1")),
                "turns", List.of(),
                "completion", Map.of("status", "in_progress"));
        Map<String, Object> data = Map.of(
                "session_id", "python-1",
                "discussion_plan", plan,
                "discussion_tree", List.of());

        when(sessions.getAndValidateSession("java-1", "student-1", "discussion")).thenReturn(session);
        when(proxy.proxyWithSessionRecovery(
                eq(session), eq("discussion"), eq("/api/v1/discussion/sessionstate/{pythonSessionId}"),
                eq(HttpMethod.GET), eq(null)))
                .thenReturn(Map.of("code", 200, "data", data));

        ObjectMapper mapper = new ObjectMapper();
        DiscussionController controller = new DiscussionController(
                sessions, proxy, mapper, mock(TaskService.class), new FeatureProperties());

        controller.getSessionState("java-1");

        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(sessions).updateStateSnapshot(eq("java-1"), snapshot.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> restored = mapper.readValue(snapshot.getValue(), Map.class);
        assertTrue(restored.containsKey("discussion_plan"));
        @SuppressWarnings("unchecked")
        Map<String, Object> restoredPlan = (Map<String, Object>) restored.get("discussion_plan");
        assertEquals(3, restoredPlan.get("revision"));
        assertEquals("n3", restoredPlan.get("active_node_id"));
    }
}
