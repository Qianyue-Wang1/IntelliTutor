package com.intellitutor.scut.modules.experiment.service;

import com.intellitutor.scut.config.FeatureProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentAccessServiceTest {

    @Test
    void nullExpectedTypeDoesNotBindAnUntypedNullParameter() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        ExperimentAccessService service = new ExperimentAccessService(jdbc, enabledFeatures());

        service.requireSessionAccess("user-1", "session-1", null);

        assertEquals(2, jdbc.calls.size());
        SqlCall access = jdbc.calls.get(1);
        assertFalse(access.sql().contains("session_type=?"));
        assertArrayEquals(new Object[]{"user-1", "session-1"}, access.args());
    }

    @Test
    void expectedTypeUsesARegularTypedEqualityParameter() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        ExperimentAccessService service = new ExperimentAccessService(jdbc, enabledFeatures());

        service.requireSessionAccess("user-1", "session-1", "explanation");

        assertEquals(2, jdbc.calls.size());
        SqlCall access = jdbc.calls.get(1);
        assertTrue(access.sql().endsWith(" AND s.session_type=?"));
        assertArrayEquals(new Object[]{"user-1", "session-1", "explanation"}, access.args());
    }

    @Test
    void sessionAccessUsesThePersonalFiveRoundStateInsteadOfTheGlobalStage() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        ExperimentAccessService service = new ExperimentAccessService(jdbc, enabledFeatures());

        service.requireSessionAccess("user-1", "session-1", null);

        String accessSql = jdbc.calls.get(1).sql();
        assertTrue(accessSql.contains("WHEN 5 THEN 'ROUND_5_TUTOR_IN_PROGRESS'"));
        assertFalse(accessSql.contains("e.current_stage_code"));
    }

    @Test
    void stageAccessUsesPersonalStateWhenTheLegacyGlobalStageIsDifferent() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.stageRow = Map.of("student_state", "WAITING_ROUND_4_QUIZ", "participation_status", "ACTIVE",
                "current_stage_code", "SETUP");
        ExperimentAccessService service = new ExperimentAccessService(jdbc, enabledFeatures());

        assertDoesNotThrow(() -> service.requireStage("user-1",
                com.intellitutor.scut.modules.experiment.domain.GlobalStage.ROUND_4_QUIZ));
    }

    private static FeatureProperties enabledFeatures() {
        FeatureProperties features = new FeatureProperties();
        features.setExperimentModeEnabled("true");
        return features;
    }

    private record SqlCall(String sql, Object[] args) {}

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<SqlCall> calls = new ArrayList<>();
        private Map<String, Object> stageRow;

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            calls.add(new SqlCall(sql, args));
            return requiredType.cast(1);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            return stageRow == null ? List.of() : List.of(stageRow);
        }
    }
}
