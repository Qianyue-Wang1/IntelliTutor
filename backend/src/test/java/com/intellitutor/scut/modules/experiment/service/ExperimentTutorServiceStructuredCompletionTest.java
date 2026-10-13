package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import com.intellitutor.scut.modules.session.service.PythonProxyService;
import com.intellitutor.scut.modules.session.service.SessionService;
import com.intellitutor.scut.modules.task.service.TaskService;
import com.intellitutor.scut.modules.task.dto.InferenceTaskMsg;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ExperimentTutorServiceStructuredCompletionTest {
    @Test
    void completesAnExperimentExplanationOnlyAfterItsPersistedSessionIsComplete() {
        JdbcTemplate jdbc = new StructuredCompletionJdbc("{\"in_summary\":true,\"current_step\":0}");
        ExperimentAccessService access = mock(ExperimentAccessService.class);
        ExperimentEventService events = mock(ExperimentEventService.class);
        ExperimentTutorService service = service(jdbc, access, events);

        assertDoesNotThrow(() -> service.completeStructured("student-1", "session-1", new ExperimentDtos.TutorCompleteReq("complete-1")));
        verify(access).requireSessionAccess("student-1", "session-1", "explanation");
        verify(events).recordSystem(eq("student-1"), eq("TUTOR_COMPLETED"), anyMap());
    }

    @Test
    void rejectsCompletionBeforeTheStructuredSessionIsComplete() {
        ExperimentTutorService service = service(new StructuredCompletionJdbc("{\"in_summary\":true,\"current_step\":1}"),
                mock(ExperimentAccessService.class), mock(ExperimentEventService.class));

        assertThrows(RuntimeException.class, () -> service.completeStructured("student-1", "session-1", new ExperimentDtos.TutorCompleteReq("complete-1")));
    }

    @Test
    void completesFullTutorWithTheReadyStatusFromItsCompletedSessionSnapshot() {
        StructuredCompletionJdbc jdbc = new StructuredCompletionJdbc(
                "{\"in_summary\":true,\"current_step\":0,\"learner_state_status\":\"READY\"}",
                "{\"data\":{\"learner_state_status\":\"PENDING\"}}");
        ExperimentEventService events = mock(ExperimentEventService.class);
        ExperimentTutorService service = service(jdbc, mock(ExperimentAccessService.class), events);

        assertDoesNotThrow(() -> service.completeStructured("student-1", "session-1", new ExperimentDtos.TutorCompleteReq("complete-1")));

        assertEquals("READY", jdbc.completedLearnerStateStatus());
        verify(events, never()).recordSystem(eq("student-1"), eq("LEARNER_STATE_FAILED"), anyMap());
    }

    @Test
    void recordsStructuredObjectiveAnswerWithItsCorrectnessForResearchExport() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), (Object[]) any())).thenReturn(List.of(Map.of(
                "id", "run-1", "assignment_id", "assignment-1", "user_id", "student-1",
                "group_code_snapshot", "BASIC_TUTOR", "mode", "explanation", "round_no", 1,
                "status", "IN_PROGRESS", "session_id", "session-1")));
        ExperimentTutorService service = service(jdbc, mock(ExperimentAccessService.class), mock(ExperimentEventService.class));
        InferenceTaskMsg message = InferenceTaskMsg.builder().taskId("task-1").sessionId("session-1")
                .taskType("EXPLANATION_COMMIT_ANSWER").userId("student-1")
                .requestBodyJson("{\"option\":\"B\",\"active_step_id\":\"1\"}").build();

        service.onInferenceSucceeded(message, Map.of("data", Map.of("current_step", 2, "error_analysis", "True")));

        verify(jdbc).update(contains("t_experiment_tutor_step_record"), any(), any(), any(), any(), any(), any(),
                eq("explanation:1"), any(), any(), any(), any(), eq("explanation:1"));
    }

    @Test
    void recordsDiscussionAnswerAgainstTheNodeThatWasActiveWhenItWasSubmitted() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), (Object[]) any())).thenReturn(List.of(Map.of(
                "id", "run-1", "assignment_id", "assignment-1", "user_id", "student-1",
                "group_code_snapshot", "FULL_TUTOR", "mode", "discussion", "round_no", 1,
                "status", "IN_PROGRESS", "session_id", "session-1")));
        ExperimentTutorService service = service(jdbc, mock(ExperimentAccessService.class), mock(ExperimentEventService.class));
        InferenceTaskMsg message = InferenceTaskMsg.builder().taskId("task-1").sessionId("session-1")
                .taskType("DISCUSSION_FEEDBACK").userId("student-1")
                .requestBodyJson("{\"feedback\":\"A\",\"submission_type\":\"option\",\"active_step_id\":\"3-1\"}").build();

        service.onInferenceSucceeded(message, Map.of("data", Map.of("correctness", true)));

        verify(jdbc).update(contains("t_experiment_tutor_step_record"), any(), any(), any(), any(), any(), any(),
                eq("discussion:3-1"), any(), any(), any(), any(), eq("discussion:3-1"));
    }

    @Test
    void recordsNextStepRetryAsInteractionInsteadOfAnotherStructuredAnswer() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), (Object[]) any())).thenReturn(List.of(Map.of(
                "id", "run-1", "assignment_id", "assignment-1", "user_id", "student-1",
                "group_code_snapshot", "BASIC_TUTOR", "mode", "discussion", "round_no", 1,
                "status", "IN_PROGRESS", "session_id", "session-1")));
        ExperimentEventService events = mock(ExperimentEventService.class);
        ExperimentTutorService service = service(jdbc, mock(ExperimentAccessService.class), events);
        InferenceTaskMsg message = InferenceTaskMsg.builder().taskId("retry-task").sessionId("session-1")
                .taskType("DISCUSSION_FEEDBACK").userId("student-1")
                .requestBodyJson("{\"feedback\":\"重试进入下一步\",\"submission_type\":\"option\"}").build();

        service.onInferenceSucceeded(message, Map.of("data", Map.of("correctness", "true", "status", "main_step")));

        verify(jdbc, never()).update(contains("t_experiment_tutor_step_record"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(jdbc, never()).update(contains("interaction_count"), any(Object[].class));
        verify(jdbc).update(contains("t_tutor_message"), any(), eq("session-1"), any(), eq("student"),
                eq("NEXT_STEP_RETRY"), eq("重试进入下一步"), isNull(), eq("session-1"));
        verify(events).recordUserAction(eq("student-1"), eq("TUTOR_NEXT_STEP_RETRIED"), anyMap(), isNull(), isNull(), eq("session-1"));
    }

    private ExperimentTutorService service(JdbcTemplate jdbc, ExperimentAccessService access, ExperimentEventService events) {
        return new ExperimentTutorService(jdbc, mock(SessionService.class), mock(TaskService.class),
                mock(ExperimentOrchestrationService.class), access, events, new ObjectMapper(), mock(PythonProxyService.class));
    }

    private static final class StructuredCompletionJdbc extends JdbcTemplate {
        private final String snapshot;
        private final String latestResult;
        private final String group;
        private String completedLearnerStateStatus;

        private StructuredCompletionJdbc(String snapshot) { this(snapshot, null, "BASIC_TUTOR"); }

        private StructuredCompletionJdbc(String snapshot, String latestResult) {
            this(snapshot, latestResult, "FULL_TUTOR");
        }

        private StructuredCompletionJdbc(String snapshot, String latestResult, String group) {
            this.snapshot = snapshot;
            this.latestResult = latestResult;
            this.group = group;
        }

        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) {
            return Map.of("id", "run-1", "assignment_id", "assignment-1", "stage_id", "stage-1", "session_id", "session-1", "group_code_snapshot", group,
                    "mode", "explanation", "round_no", 1, "status", "IN_PROGRESS", "session_type", "explanation",
                    "state_snapshot", snapshot);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            return latestResult == null ? List.of() : List.of(Map.of("result", latestResult));
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("learner_state_status")) completedLearnerStateStatus = String.valueOf(args[0]);
            return 1;
        }

        private String completedLearnerStateStatus() { return completedLearnerStateStatus; }
    }
}
