package com.intellitutor.scut.modules.session.controller;

import com.intellitutor.scut.common.utils.UserContext;
import com.intellitutor.scut.config.FeatureProperties;
import com.intellitutor.scut.modules.session.service.PythonProxyService;
import com.intellitutor.scut.modules.experiment.service.ExperimentAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolProxyControllerSecurityTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void knowledgeGraphForwardsAuthenticatedUserId() {
        PythonProxyService proxyService = mock(PythonProxyService.class);
        FeatureProperties features = new FeatureProperties();
        ExperimentAccessService experimentAccess = mock(ExperimentAccessService.class);
        ToolProxyController controller = new ToolProxyController(proxyService, features, experimentAccess);
        UserContext.setUserId("user-123");

        when(proxyService.proxy(
                "/api/v1/user/knowledge-graph?user_id=user-123",
                HttpMethod.GET,
                null)).thenReturn(Map.of("code", 200));

        controller.getKnowledgeGraph();

        verify(proxyService).proxy(
                "/api/v1/user/knowledge-graph?user_id=user-123",
                HttpMethod.GET,
                null);
    }
}
