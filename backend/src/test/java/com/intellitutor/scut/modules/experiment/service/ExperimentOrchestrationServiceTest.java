package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.config.FeatureProperties;
import com.intellitutor.scut.modules.experiment.dto.ExperimentContextVO;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ExperimentOrchestrationServiceTest {

    @Test
    void locksTheFirstModeChoiceAndMovesTheStudentToRoundOne() {
        AssignmentJdbcTemplate jdbc = new AssignmentJdbcTemplate();
        ExperimentOrchestrationService service = new ExperimentOrchestrationService(jdbc, new ObjectMapper(), enabledFeatures(),
                mock(ExperimentEventService.class), mock(ExperimentAccessService.class));

        ExperimentDtos.CommandResult result = service.selectMode("user-1",
                new ExperimentDtos.SelectModeReq("DIRECT_LLM", "select-direct", 2));

        assertEquals("DIRECT_LLM", result.selectedMode());
        assertEquals("WAITING_ROUND_1_TUTOR", result.studentState());
        assertEquals(3, result.stateVersion());
        assertEquals("DIRECT_LLM", jdbc.assignment.get("selected_mode"));
        assertEquals("WAITING_ROUND_1_TUTOR", jdbc.assignment.get("student_state"));
    }

    @Test
    void letsBasicTutorChooseAnExplanationMethodForTheCurrentRound() {
        AssignmentJdbcTemplate jdbc = new AssignmentJdbcTemplate();
        jdbc.assignment.put("selected_mode", "BASIC_TUTOR");
        jdbc.assignment.put("student_state", "WAITING_ROUND_1_TUTOR");
        jdbc.assignment.put("current_round", 1);
        jdbc.assignment.put("state_version", 3L);
        ExperimentOrchestrationService service = new ExperimentOrchestrationService(jdbc, new ObjectMapper(), enabledFeatures(),
                mock(ExperimentEventService.class), mock(ExperimentAccessService.class));

        ExperimentDtos.CommandResult result = service.selectLearningMethod("user-1",
                new ExperimentDtos.SelectLearningMethodReq(1, "explanation", "method-1", 3));

        assertEquals("BASIC_TUTOR", result.selectedMode());
        assertEquals("ROUND_1_TUTOR_IN_PROGRESS", result.studentState());
        assertEquals("explanation", jdbc.assignment.get("current_learning_method"));
    }

    @Test
    void contextRoutesFromThePersonalStateEvenWhenTheLegacyGlobalStageIsSetup() {
        AssignmentJdbcTemplate jdbc = new AssignmentJdbcTemplate();
        jdbc.assignment.put("selected_mode", "DIRECT_LLM");
        jdbc.assignment.put("student_state", "WAITING_ROUND_1_TUTOR");
        jdbc.assignment.put("current_round", 1);
        jdbc.assignment.put("current_stage_code", "SETUP");
        ExperimentOrchestrationService service = new ExperimentOrchestrationService(jdbc, new ObjectMapper(), enabledFeatures(),
                mock(ExperimentEventService.class), mock(ExperimentAccessService.class));

        ExperimentContextVO context = service.context("user-1");

        assertEquals("/experiment/tutor/start", context.nextRoute());
        assertEquals(List.of("START_TUTOR"), context.allowedActions());
        assertEquals(2, context.stateVersion());
    }

    @Test
    void contextRoutesANewLocalExperimentDirectlyToModeSelection() {
        AssignmentJdbcTemplate jdbc = new AssignmentJdbcTemplate();
        ExperimentOrchestrationService service = new ExperimentOrchestrationService(jdbc, new ObjectMapper(), enabledFeatures(),
                mock(ExperimentEventService.class), mock(ExperimentAccessService.class));

        ExperimentContextVO context = service.context("user-1");

        assertEquals("/experiment/mode", context.nextRoute());
        assertEquals(List.of("SELECT_MODE"), context.allowedActions());
    }

    @Test
    void contextKeepsTutorLaunchRouteUntilPythonSessionIsInitialized() {
        AssignmentJdbcTemplate jdbc = new AssignmentJdbcTemplate();
        jdbc.assignment.put("selected_mode", "BASIC_TUTOR");
        jdbc.assignment.put("current_learning_method", "explanation");
        jdbc.assignment.put("student_state", "ROUND_3_TUTOR_IN_PROGRESS");
        jdbc.assignment.put("current_round", 3);
        jdbc.tutorResource = new HashMap<>(Map.of("mode", "explanation", "session_id", "session-3"));
        jdbc.tutorResource.put("python_session_id", null);
        ExperimentOrchestrationService service = new ExperimentOrchestrationService(jdbc, new ObjectMapper(), enabledFeatures(),
                mock(ExperimentEventService.class), mock(ExperimentAccessService.class));

        ExperimentContextVO context = service.context("user-1");

        assertEquals("/experiment/tutor/start", context.nextRoute());
        assertEquals(List.of("START_TUTOR"), context.allowedActions());
    }

    @Test
    void contextRoutesCompletedLocalExperimentToItsExportRecords() {
        AssignmentJdbcTemplate jdbc = new AssignmentJdbcTemplate();
        jdbc.assignment.put("student_state", "EXPERIMENT_FINISHED");
        ExperimentOrchestrationService service = new ExperimentOrchestrationService(jdbc, new ObjectMapper(), enabledFeatures(),
                mock(ExperimentEventService.class), mock(ExperimentAccessService.class));

        ExperimentContextVO context = service.context("user-1");

        assertEquals("/experiment/records", context.nextRoute());
    }

    @Test
    void duplicateModeCommandReturnsItsOriginalResultWithoutASecondTransition() {
        AssignmentJdbcTemplate jdbc = new AssignmentJdbcTemplate();
        ExperimentOrchestrationService service = new ExperimentOrchestrationService(jdbc, new ObjectMapper(), enabledFeatures(),
                mock(ExperimentEventService.class), mock(ExperimentAccessService.class));
        ExperimentDtos.SelectModeReq request = new ExperimentDtos.SelectModeReq("DIRECT_LLM", "select-direct", 2);

        ExperimentDtos.CommandResult first = service.selectMode("user-1", request);
        ExperimentDtos.CommandResult duplicate = service.selectMode("user-1", request);

        assertEquals(first, duplicate);
        assertEquals(3L, jdbc.assignment.get("state_version"));
    }

    @Test
    void aNewModeCommandCannotReplaceAnAlreadyLockedMode() {
        AssignmentJdbcTemplate jdbc = new AssignmentJdbcTemplate();
        jdbc.assignment.put("selected_mode", "DIRECT_LLM");
        jdbc.assignment.put("student_state", "WAITING_ROUND_1_TUTOR");
        jdbc.assignment.put("current_round", 1);
        jdbc.assignment.put("state_version", 3L);
        ExperimentOrchestrationService service = new ExperimentOrchestrationService(jdbc, new ObjectMapper(), enabledFeatures(),
                mock(ExperimentEventService.class), mock(ExperimentAccessService.class));

        assertThrows(RuntimeException.class, () -> service.selectMode("user-1",
                new ExperimentDtos.SelectModeReq("FULL_TUTOR", "change-mode", 3)));
    }

    private static FeatureProperties enabledFeatures() {
        FeatureProperties features = new FeatureProperties();
        features.setExperimentModeEnabled("true");
        return features;
    }

    private static final class AssignmentJdbcTemplate extends JdbcTemplate {
        private final Map<String, Object> assignment = assignment();
        private final Map<String, String> commands = new HashMap<>();
        private Map<String, Object> tutorResource;

        private static Map<String, Object> assignment() {
            Map<String, Object> row = new HashMap<>();
            row.put("id", "assignment-1");
            row.put("experiment_id", "experiment-1");
            row.put("participation_status", "ACTIVE");
            row.put("student_state", "WAITING_MODE_SELECTION");
            row.put("selected_mode", null);
            row.put("current_round", 0);
            row.put("state_version", 2L);
            row.put("content_version", "unsupervised-learning-v1");
            return row;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("FOR UPDATE OF a")) return List.of(assignment);
            if (sql.contains("t_experiment_command")) {
                String response = commands.get(args[1] + ":" + args[2]);
                return response == null ? List.of() : List.of(Map.of("response_json", response));
            }
            if (sql.contains("ORDER BY a.created_at DESC LIMIT 1")) return List.of(assignment);
            if (sql.contains("t_experiment_tutor_run")) return tutorResource == null ? List.of() : List.of(tutorResource);
            if (sql.contains("t_assessment_attempt")) return List.of();
            throw new AssertionError("未预期查询: " + sql);
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("t_student_experiment_assignment") && sql.contains("selected_mode")) {
                assignment.put("selected_mode", args[0]);
                assignment.put("student_state", "WAITING_ROUND_1_TUTOR");
                assignment.put("current_round", 1);
                assignment.put("state_version", 3L);
            }
            if (sql.contains("t_student_experiment_assignment") && sql.contains("current_learning_method")) {
                assignment.put("current_learning_method", args[0]);
                assignment.put("student_state", args[1]);
                assignment.put("state_version", 4L);
            }
            if (sql.contains("INSERT INTO t_experiment_command")) {
                commands.put(args[2] + ":" + args[3], String.valueOf(args[4]));
            }
            return 1;
        }
    }
}
