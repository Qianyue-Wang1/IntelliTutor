package com.intellitutor.scut.modules.session.controller;

import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.config.FeatureProperties;
import com.intellitutor.scut.modules.session.dto.req.DiscussionInitReq;
import com.intellitutor.scut.modules.session.dto.req.ExplanationInitReq;
import com.intellitutor.scut.modules.session.dto.req.ImageData;
import com.intellitutor.scut.modules.session.service.PythonProxyService;
import com.intellitutor.scut.modules.experiment.service.ExperimentAccessService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageCapabilityTest {

    @Test
    void imageToolsAreRejectedWhenDisabled() {
        PythonProxyService proxyService = mock(PythonProxyService.class);
        FeatureProperties features = new FeatureProperties();
        ToolProxyController controller = new ToolProxyController(proxyService, features, mock(ExperimentAccessService.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.imgInput(Map.of("imgStr", "data:image/png;base64,AA")));

        assertEquals(ResultCode.FEATURE_DISABLED, error.getResultCode());

        BusinessException codeError = assertThrows(BusinessException.class,
                () -> controller.imgToCode(Map.of("imgStr", "data:image/png;base64,AA")));
        assertEquals(ResultCode.FEATURE_DISABLED, codeError.getResultCode());
    }

    @Test
    void imageToolsAreForwardedWhenEnabled() {
        PythonProxyService proxyService = mock(PythonProxyService.class);
        FeatureProperties features = new FeatureProperties();
        features.setImageInputEnabled("true");
        ToolProxyController controller = new ToolProxyController(proxyService, features, mock(ExperimentAccessService.class));
        Map<String, Object> body = Map.of("imgStr", "data:image/png;base64,AA");
        when(proxyService.proxy("/api/v1/imginput", org.springframework.http.HttpMethod.POST, body))
                .thenReturn(Map.of("code", 200));

        controller.imgInput(body);

        verify(proxyService).proxy("/api/v1/imginput", org.springframework.http.HttpMethod.POST, body);
    }

    @Test
    void explanationInitRejectsImagesWhenDisabled() {
        FeatureProperties features = new FeatureProperties();
        ExplanationController controller = new ExplanationController(null, null, null, null, features);
        ExplanationInitReq request = new ExplanationInitReq();
        request.setProcessedQuestion("question");
        request.setImages(List.of(new ImageData("question.png", "data:image/png;base64,AA")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.initExplanation(request));

        assertEquals(ResultCode.FEATURE_DISABLED, error.getResultCode());
    }

    @Test
    void discussionInitRejectsImageCodeWhenDisabled() {
        FeatureProperties features = new FeatureProperties();
        DiscussionController controller = new DiscussionController(null, null, null, null, features);
        DiscussionInitReq request = new DiscussionInitReq();
        request.setProcessedQuestion("question");
        request.setImageCode(List.of("A=(0,0)"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.initDiscussion(request));

        assertEquals(ResultCode.FEATURE_DISABLED, error.getResultCode());
    }
}
