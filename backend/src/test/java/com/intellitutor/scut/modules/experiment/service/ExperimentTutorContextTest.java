package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.modules.experiment.domain.ExperimentGroup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentTutorContextTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void basicContextIncludesOnlyTopicObjectivesAndNoPretestSummary() throws Exception {
        JsonNode round = objectMapper.readTree("""
                {"topic":"K-means 聚类","objectives":["说明中心更新"],"prompt":"不得发送"}
                """);

        String context = ExperimentTutorService.modelQuestion(ExperimentGroup.BASIC_TUTOR, round, "备用题目", List.of());

        assertTrue(context.contains("K-means 聚类"));
        assertFalse(context.contains("P1:正确"));
        assertFalse(context.contains("P2:错误"));
        assertFalse(context.contains("不得发送"));
        assertFalse(context.contains("备用题目"));
        assertFalse(context.contains("跨轮学习状态"));
    }

    @Test
    void fullContextAddsOnlyStructuredPriorRoundState() throws Exception {
        JsonNode round = objectMapper.readTree("""
                {"topic":"PCA","objectives":["说明信息损失"]}
                """);

        String context = ExperimentTutorService.modelQuestion(ExperimentGroup.FULL_TUTOR, round, "备用题目",
                List.of(Map.of("round", 1, "mode", "discussion", "incorrectCount", 1)));

        assertTrue(context.contains("跨轮学习状态"));
        assertTrue(context.contains("round=1"));
        assertTrue(context.contains("incorrectCount=1"));
        assertFalse(context.contains("备用题目"));
    }

    @Test
    void directContextUsesTheLockedDirectPromptOnly() throws Exception {
        JsonNode round = objectMapper.readTree("""
                {"topic":"PCA","objectives":["说明信息损失"],"directPrompt":"只讲 PCA"}
                """);

        String context = ExperimentTutorService.modelQuestion(ExperimentGroup.DIRECT_LLM, round, "备用题目", List.of(Map.of("round", 1)));

        assertTrue(context.contains("只讲 PCA"));
        assertFalse(context.contains("P1:错误"));
        assertFalse(context.contains("跨轮学习状态"));
        assertFalse(context.contains("备用题目"));
    }

    @Test
    void explicitFullContextKeepsOnlyApprovedStructuredFields() throws Exception {
        JsonNode round = objectMapper.readTree("""
                {"topic":"K-means","objectives":["说明中心更新"],"prompt":"不得发送"}
                """);

        var context = ExperimentTutorService.teachingContext(ExperimentGroup.FULL_TUTOR, round,
                List.of(Map.of("round", 1, "learnerState", "READY", "freeText", "不得发送")));

        assertTrue(context.objectives().contains("说明中心更新"));
        assertTrue(context.priorRoundState().get(0).containsKey("learnerState"));
        assertFalse(context.priorRoundState().get(0).containsKey("freeText"));
    }
}
