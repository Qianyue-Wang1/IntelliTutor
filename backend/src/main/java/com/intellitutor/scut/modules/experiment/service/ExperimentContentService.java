package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ExperimentContentService {
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public ExperimentContentService(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    @Autowired
    public ExperimentContentService(ObjectMapper objectMapper, JdbcTemplate jdbc) {
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    public JsonNode readClasspathPackage(String path) {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readTree(input);
        } catch (IOException exception) {
            throw new IllegalArgumentException("实验内容包不可读取: " + path, exception);
        }
    }

    public List<String> validate(JsonNode content) {
        List<String> errors = new ArrayList<>();
        if (content.path("rounds").size() != 5) errors.add("必须配置五个学习轮次");
        Set<String> roundCodes = new HashSet<>();
        for (JsonNode round : content.path("rounds")) {
            if (!round.path("code").isTextual() || !roundCodes.add(round.path("code").asText())) {
                errors.add("学习轮次编号必须唯一");
            }
        }
        if (content.path("assessments").has("pretest") && content.path("assessments").path("pretest").size() != 5) {
            errors.add("前测必须包含五题");
        }
        if (content.path("assessments").path("posttest").size() != 10) errors.add("后测必须包含十题");
        for (int round = 1; round <= 5; round++) {
            JsonNode roundNode = content.path("rounds").path(round - 1);
            if (roundNode.path("objectives").isEmpty()) errors.add("M" + round + " 缺少学习目标");
            if (content.path("assessments").path("quizzes").path("Q" + round).size() != 3) {
                errors.add("Q" + round + " 必须包含三题");
            }
        }
        if (content.path("assessments").has("pretest")
                && content.path("durations").path("pretestSeconds").asInt() != 360) {
            errors.add("前测时长必须为360秒");
        }
        if (content.path("durations").path("quizSeconds").asInt() != 150) errors.add("轮测时长必须为150秒");
        if (content.path("durations").path("posttestSeconds").asInt() != 720) errors.add("后测时长必须为720秒");
        Set<String> codes = new HashSet<>();
        content.path("assessments").forEach(section -> collectCodes(section, codes, errors));
        content.path("assessments").forEach(section -> validateItems(section, errors));
        return errors;
    }

    public String publish(String experimentId, String adminId, JsonNode content) {
        requireJdbc();
        List<String> errors = validate(content);
        if (!errors.isEmpty()) throw new IllegalArgumentException("实验内容包无效: " + String.join("；", errors));
        String version = content.path("version").asText();
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM t_experiment_content_version WHERE experiment_id=? AND version=?",
                Integer.class, experimentId, version);
        if (existing != null && existing > 0) throw new IllegalArgumentException("实验内容版本已发布: " + version);
        try {
            jdbc.update("INSERT INTO t_experiment_content_version(id,experiment_id,version,package_json,model_version,prompt_version,published_by) " +
                            "VALUES(?,?,?,?::jsonb,?,?,?)",
                    java.util.UUID.randomUUID().toString(), experimentId, version, objectMapper.writeValueAsString(content),
                    content.path("modelVersion").asText(), content.path("promptVersion").asText(), adminId);
        } catch (IOException exception) {
            throw new IllegalArgumentException("实验内容包不可序列化", exception);
        }
        return version;
    }

    public void lockForAssignment(String assignmentId, String contentVersion) {
        requireJdbc();
        int updated = jdbc.update("UPDATE t_student_experiment_assignment SET content_version=?,updated_at=NOW() " +
                "WHERE id=? AND content_version IS NULL", contentVersion, assignmentId);
        if (updated != 1) throw new IllegalStateException("实验内容版本已锁定或学生不存在");
    }

    public JsonNode contentForAssignment(String assignmentId) {
        requireJdbc();
        String content = jdbc.queryForObject("SELECT cv.package_json::text FROM t_student_experiment_assignment a " +
                        "JOIN t_experiment_content_version cv ON cv.experiment_id=a.experiment_id AND a.content_version=cv.version " +
                        "WHERE a.id=?",
                String.class, assignmentId);
        try {
            return objectMapper.readTree(content);
        } catch (IOException exception) {
            throw new IllegalArgumentException("已锁定实验内容不可读取", exception);
        }
    }

    public AssessmentDefinition assessmentDefinition(JsonNode content, String assessmentCode) {
        JsonNode items;
        int duration;
        String title;
        if ("PRETEST".equals(assessmentCode)) {
            items = content.path("assessments").path("pretest");
            duration = content.path("durations").path("pretestSeconds").asInt();
            title = "前测";
        } else if ("POSTTEST".equals(assessmentCode)) {
            items = content.path("assessments").path("posttest");
            duration = content.path("durations").path("posttestSeconds").asInt();
            title = "后测";
        } else if (assessmentCode != null && assessmentCode.matches("Q[1-5]")) {
            items = content.path("assessments").path("quizzes").path(assessmentCode);
            duration = content.path("durations").path("quizSeconds").asInt();
            title = "第" + assessmentCode.substring(1) + "轮小后测";
        } else {
            throw new IllegalArgumentException("未知正式测验: " + assessmentCode);
        }
        if (!items.isArray() || items.isEmpty() || duration <= 0) {
            throw new IllegalArgumentException("正式测验配置无效: " + assessmentCode);
        }
        return new AssessmentDefinition(assessmentCode, title, duration, items.deepCopy());
    }

    private void collectCodes(JsonNode node, Set<String> codes, List<String> errors) {
        if (node.isArray()) {
            for (JsonNode item : node) collectCodes(item, codes, errors);
            return;
        }
        if (!node.isObject()) return;
        if (node.hasNonNull("code") && !codes.add(node.path("code").asText())) errors.add("题目编号重复");
        node.forEach(child -> collectCodes(child, codes, errors));
    }

    private void validateItems(JsonNode node, List<String> errors) {
        if (node.isArray()) {
            for (JsonNode item : node) validateItems(item, errors);
            return;
        }
        if (!node.isObject()) return;
        if (node.has("code")) {
            String code = node.path("code").asText();
            if (!node.path("prompt").isTextual() || node.path("prompt").asText().isBlank()) {
                errors.add("题目缺少题干: " + code);
            }
            if (!node.path("options").isArray() || node.path("options").size() != 4) {
                errors.add("题目选项必须固定为四项: " + code);
            }
            if (!node.path("answerKey").isTextual() || node.path("answerKey").asText().isBlank()) {
                errors.add("题目缺少答案键: " + code);
            }
        }
        node.forEach(child -> validateItems(child, errors));
    }

    private void requireJdbc() {
        if (jdbc == null) throw new IllegalStateException("内容版本存储未配置");
    }

    public record AssessmentDefinition(String code, String title, int durationSeconds, JsonNode items) {}
}
