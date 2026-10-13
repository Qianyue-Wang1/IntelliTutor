package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.intellitutor.scut.config.FeatureProperties;
import com.intellitutor.scut.modules.experiment.domain.ExperimentGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class DefaultMachineLearningExperimentBootstrap implements ApplicationRunner {
    private static final String CONTENT_PATH = "experiments/unsupervised-learning-v2.json";

    private final FeatureProperties features;
    private final JdbcTemplate jdbc;
    private final ExperimentContentService content;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!features.isExperimentModeEnabled()) return;

        JsonNode packageJson = content.readClasspathPackage(CONTENT_PATH);
        if (!content.validate(packageJson).isEmpty()) {
            throw new IllegalStateException("默认机器学习实验内容包无效");
        }

        List<Map<String, Object>> existing = jdbc.queryForList("SELECT id FROM t_experiment WHERE code='machine-learning' AND status='ACTIVE'");
        if (!existing.isEmpty()) {
            activateExisting(String.valueOf(existing.get(0).get("id")), packageJson);
            return;
        }
        if (activeExperimentCount() > 0) return;

        String experimentId = id();
        String version = packageJson.path("version").asText();
        jdbc.update("INSERT INTO t_experiment(id,code,name,config_version,status,current_stage_code,registration_policy,content_version,started_at) " +
                    "VALUES(?,?,?,?,'ACTIVE','SETUP','LOCAL',?,NOW())",
                experimentId, "machine-learning", packageJson.path("course").path("name").asText(), version, version);
        for (ExperimentGroup group : ExperimentGroup.values()) {
            jdbc.update("INSERT INTO t_experiment_group(id,experiment_id,code,display_name) VALUES(?,?,?,?)",
                    id(), experimentId, group.name(), group.studentMode().name());
        }
        content.publish(experimentId, null, packageJson);
        for (int round = 1; round <= 5; round++) createTutorRound(experimentId, packageJson, round);
    }

    private void activateExisting(String experimentId, JsonNode packageJson) {
        String version = packageJson.path("version").asText();
        Integer existingVersion = jdbc.queryForObject("SELECT COUNT(*) FROM t_experiment_content_version WHERE experiment_id=? AND version=?",
                Integer.class, experimentId, version);
        if (existingVersion == null || existingVersion == 0) content.publish(experimentId, null, packageJson);
        jdbc.update("UPDATE t_experiment SET content_version=?,config_version=?,updated_at=NOW() WHERE id=?",
                version, version, experimentId);
    }

    private int activeExperimentCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM t_experiment WHERE status='ACTIVE'", Integer.class);
        return count == null ? 0 : count;
    }

    private void createTutorRound(String experimentId, JsonNode packageJson, int round) {
        String questionId = id();
        String stageId = id();
        String code = "ROUND_" + round + "_TUTOR";
        String topic = packageJson.path("rounds").path(round - 1).path("topic").asText();
        jdbc.update("INSERT INTO t_experiment_question(id,experiment_id,code,question_type,prompt,metadata) VALUES(?,?,?,'TUTOR',?,'{}'::jsonb)",
                questionId, experimentId, "M" + round + "_TUTOR", topic);
        jdbc.update("INSERT INTO t_experiment_stage(id,experiment_id,stage_code,stage_type,sequence_no,round_no,title) VALUES(?,?,?,'TUTOR',?,?,?)",
                stageId, experimentId, code, round, round, "第" + round + "轮学习");
        for (ExperimentGroup group : ExperimentGroup.values()) {
            boolean participating = group.participatesInTutor();
            String policy = group == ExperimentGroup.DIRECT_LLM ? "DIRECT" :
                    group == ExperimentGroup.BLANK_CONTROL ? "SKIP" : "STUDENT_CHOICE";
            jdbc.update("INSERT INTO t_experiment_stage_group_config(id,stage_id,group_code,participating,tutor_question_id,mode_policy) VALUES(?,?,?,?,?,?)",
                    id(), stageId, group.name(), participating, participating ? questionId : null, policy);
        }
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
