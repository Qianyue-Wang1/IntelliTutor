package com.intellitutor.scut.modules.session.controller;

import com.intellitutor.scut.config.FeatureProperties;
import com.intellitutor.scut.common.utils.UserContext;
import com.intellitutor.scut.modules.session.service.PythonProxyService;
import com.intellitutor.scut.modules.experiment.service.ExperimentAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 无状态工具类接口同步代理。
 *
 * <p>
 * 这些接口保持前端既有路径和响应结构，Java 只负责把请求透传到 Python 推理端。
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ToolProxyController {

    private final PythonProxyService proxyService;
    private final FeatureProperties features;
    private final ExperimentAccessService experimentAccess;

    @PostMapping("/checkinput")
    public Map<String, Object> checkInput(@RequestBody Map<String, Object> body) {
        guardExperimentStudent();
        return proxyService.proxy("/api/v1/checkinput", HttpMethod.POST, body);
    }

    @PostMapping("/imginput")
    public Map<String, Object> imgInput(@RequestBody Map<String, Object> body) {
        guardExperimentStudent();
        features.requireImageInputEnabled();
        return proxyService.proxy("/api/v1/imginput", HttpMethod.POST, body);
    }

    @PostMapping("/imgtocode")
    public Map<String, Object> imgToCode(@RequestBody Map<String, Object> body) {
        guardExperimentStudent();
        features.requireImageInputEnabled();
        return proxyService.proxy("/api/v1/imgtocode", HttpMethod.POST, body);
    }

    @GetMapping("/user/knowledge-graph")
    public Map<String, Object> getKnowledgeGraph() {
        guardExperimentStudent();
        String userId = URLEncoder.encode(UserContext.getUserId(), StandardCharsets.UTF_8);
        return proxyService.proxy(
                "/api/v1/user/knowledge-graph?user_id=" + userId,
                HttpMethod.GET,
                null);
    }

    private void guardExperimentStudent() {
        experimentAccess.rejectLegacyTutorForExperimentStudent(UserContext.getUserId());
    }
}
