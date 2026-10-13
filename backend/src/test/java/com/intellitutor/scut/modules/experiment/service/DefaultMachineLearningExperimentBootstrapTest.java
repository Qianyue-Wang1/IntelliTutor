package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.config.FeatureProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMachineLearningExperimentBootstrapTest {

    @Test
    void createsOnePublishedFiveRoundExperimentWhenNoneIsActive() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(0);
        DefaultMachineLearningExperimentBootstrap bootstrap = bootstrap(jdbc);

        bootstrap.run(null);

        assertTrue(jdbc.updates.stream().anyMatch(call -> call.sql().contains("INSERT INTO t_experiment")
                && call.sql().contains("'ACTIVE'") && "unsupervised-learning-v2".equals(call.args()[4])));
        assertTrue(jdbc.updates.stream().anyMatch(call -> call.sql().contains("INSERT INTO t_experiment")
                && call.sql().contains("'LOCAL'")));
        assertTrue(jdbc.updates.stream().anyMatch(call -> call.sql().contains("t_experiment_content_version")));
        assertEquals(5, jdbc.updates.stream().filter(call -> call.sql().contains("INSERT INTO t_experiment_stage")
                && String.valueOf(call.args()[2]).matches("ROUND_[1-5]_TUTOR")).count());
        assertEquals(20, jdbc.updates.stream().filter(call -> call.sql().contains("t_experiment_stage_group_config")).count());
    }

    @Test
    void publishesV2ForAnExistingActiveMachineLearningExperiment() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(1);

        bootstrap(jdbc).run(null);

        assertTrue(jdbc.updates.stream().anyMatch(call -> call.sql().contains("t_experiment_content_version")));
        assertTrue(jdbc.updates.stream().anyMatch(call -> call.sql().contains("UPDATE t_experiment SET content_version")));
    }

    private DefaultMachineLearningExperimentBootstrap bootstrap(RecordingJdbcTemplate jdbc) {
        FeatureProperties features = new FeatureProperties();
        features.setExperimentModeEnabled("true");
        return new DefaultMachineLearningExperimentBootstrap(features, jdbc,
                new ExperimentContentService(new ObjectMapper(), jdbc));
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final int activeExperiments;
        private final List<UpdateCall> updates = new ArrayList<>();

        private RecordingJdbcTemplate(int activeExperiments) {
            this.activeExperiments = activeExperiments;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (sql.contains("COUNT(*) FROM t_experiment") && sql.contains("status='ACTIVE'")) {
                return requiredType.cast(activeExperiments);
            }
            if (sql.contains("COUNT(*) FROM t_experiment_content_version")) return requiredType.cast(0);
            throw new AssertionError("Unexpected query: " + sql);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            return queryForObject(sql, requiredType, new Object[0]);
        }

        @Override
        public List<java.util.Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("code='machine-learning'")) {
                return activeExperiments == 0 ? List.of() : List.of(java.util.Map.of("id", "experiment-1"));
            }
            throw new AssertionError("Unexpected query: " + sql);
        }

        @Override
        public List<java.util.Map<String, Object>> queryForList(String sql) {
            return queryForList(sql, new Object[0]);
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(new UpdateCall(sql, args));
            return 1;
        }

        private record UpdateCall(String sql, Object[] args) {}
    }
}
