package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentContentServiceTest {

    private final ExperimentContentService service = new ExperimentContentService(new ObjectMapper());

    @Test
    void validatesTheCompleteFiveRoundUnsupervisedLearningPackage() {
        JsonNode content = service.readClasspathPackage("experiments/unsupervised-learning-v1.json");

        assertTrue(service.validate(content).isEmpty());
        assertEquals(5, content.path("rounds").size());
        assertEquals(5, content.path("assessments").path("pretest").size());
        assertEquals(10, content.path("assessments").path("posttest").size());
        for (int round = 1; round <= 5; round++) {
            assertEquals(3, content.path("assessments").path("quizzes").path("Q" + round).size());
        }
        assertEquals(360, content.path("durations").path("pretestSeconds").asInt());
        assertEquals(150, content.path("durations").path("quizSeconds").asInt());
        assertEquals(720, content.path("durations").path("posttestSeconds").asInt());
    }

    @Test
    void allowsANewPackageWithoutAPretest() {
        JsonNode content = service.readClasspathPackage("experiments/unsupervised-learning-v1.json").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) content).put("version", "unsupervised-learning-v2");
        ((com.fasterxml.jackson.databind.node.ObjectNode) content.path("assessments")).remove("pretest");
        ((com.fasterxml.jackson.databind.node.ObjectNode) content.path("durations")).remove("pretestSeconds");

        assertTrue(service.validate(content).isEmpty());
    }

    @Test
    void keepsEveryScoredItemServerCompleteAndUniquelyCoded() {
        JsonNode content = service.readClasspathPackage("experiments/unsupervised-learning-v1.json");
        Set<String> codes = new HashSet<>();

        assertItems(content.path("assessments").path("pretest"), 5, codes);
        for (int round = 1; round <= 5; round++) {
            assertItems(content.path("assessments").path("quizzes").path("Q" + round), 3, codes);
        }
        assertItems(content.path("assessments").path("posttest"), 10, codes);
        assertEquals(30, codes.size());
    }

    @Test
    void rejectsAnInvalidScoredItemBeforeItCanBePublished() {
        JsonNode content = service.readClasspathPackage("experiments/unsupervised-learning-v1.json").deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) content.path("assessments").path("pretest").path(0).path("options"))
                .remove(3);
        ((com.fasterxml.jackson.databind.node.ObjectNode) content.path("assessments").path("pretest").path(0))
                .remove("answerKey");

        assertFalse(service.validate(content).isEmpty());
    }

    @Test
    void rejectsDuplicateRoundCodesBeforeItCanBePublished() {
        JsonNode content = service.readClasspathPackage("experiments/unsupervised-learning-v1.json").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) content.path("rounds").path(1)).put("code", "M1");

        assertFalse(service.validate(content).isEmpty());
    }

    @Test
    void resolvesTheAssignmentLockedVersionAfterANewerVersionIsPublished() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ContentJdbcTemplate jdbc = new ContentJdbcTemplate();
        ExperimentContentService persistentService = new ExperimentContentService(mapper, jdbc);
        JsonNode v1 = persistentService.readClasspathPackage("experiments/unsupervised-learning-v1.json");
        JsonNode v2 = v1.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) v2).put("version", "unsupervised-learning-v2");
        jdbc.content = mapper.writeValueAsString(v1);

        persistentService.publish("experiment-1", "admin-1", v1);
        persistentService.lockForAssignment("assignment-1", "unsupervised-learning-v1");
        persistentService.publish("experiment-1", "admin-1", v2);

        assertEquals("unsupervised-learning-v1", persistentService.contentForAssignment("assignment-1").path("version").asText());
        assertTrue(jdbc.resolvedWithAssignmentVersion);
    }

    @Test
    void resolvesOnlyTheRequestedFormalAssessmentAndItsFixedDuration() {
        JsonNode content = service.readClasspathPackage("experiments/unsupervised-learning-v1.json");

        ExperimentContentService.AssessmentDefinition pretest = service.assessmentDefinition(content, "PRETEST");
        ExperimentContentService.AssessmentDefinition quiz = service.assessmentDefinition(content, "Q4");
        ExperimentContentService.AssessmentDefinition posttest = service.assessmentDefinition(content, "POSTTEST");

        assertEquals(360, pretest.durationSeconds());
        assertEquals(5, pretest.items().size());
        assertEquals(150, quiz.durationSeconds());
        assertEquals("Q4.1", quiz.items().path(0).path("code").asText());
        assertEquals(720, posttest.durationSeconds());
        assertEquals(10, posttest.items().size());
    }

    private void assertItems(JsonNode items, int expectedCount, Set<String> codes) {
        assertEquals(expectedCount, items.size());
        for (JsonNode item : items) {
            assertTrue(item.path("code").isTextual() && !item.path("code").asText().isBlank());
            assertTrue(codes.add(item.path("code").asText()), "题目编号必须唯一");
            assertTrue(item.path("prompt").isTextual() && !item.path("prompt").asText().isBlank());
            assertEquals(4, item.path("options").size());
            assertTrue(item.path("answerKey").isTextual() && !item.path("answerKey").asText().isBlank());
        }
    }

    private static final class ContentJdbcTemplate extends JdbcTemplate {
        private String content;
        private boolean resolvedWithAssignmentVersion;

        @Override
        public int update(String sql, Object... args) {
            return 1;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (sql.contains("COUNT(*)")) return requiredType.cast(0);
            if (sql.contains("a.content_version=cv.version")) {
                resolvedWithAssignmentVersion = true;
                return requiredType.cast(content);
            }
            throw new AssertionError("未预期查询: " + sql);
        }
    }
}
