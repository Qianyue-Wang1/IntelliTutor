package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NoAiLearningServiceTest {

    @Test
    void servesOnlyTheLockedLectureAndRequiresRecordedBottomBeforeCompletion() throws Exception {
        NoAiJdbcTemplate jdbc = new NoAiJdbcTemplate();
        ExperimentContentService content = mock(ExperimentContentService.class);
        when(content.contentForAssignment("assignment-1")).thenReturn(new ObjectMapper().readTree("""
                {"noAiLectures":{"M1":"# 固定讲义\\n\\n这是一段无 AI 对照组讲义。"},
                 "assessments":{"quizzes":{"Q1":[{"answerKey":"A"}]}}}
                """));
        NoAiLearningService service = new NoAiLearningService(jdbc, content, mock(ExperimentEventService.class));

        ExperimentDtos.NoAiLectureVO lecture = service.loadLecture("student-1");

        assertEquals(1, lecture.round());
        assertEquals("# 固定讲义\n\n这是一段无 AI 对照组讲义。", lecture.markdown());
        assertEquals("ROUND_1_TUTOR_IN_PROGRESS", jdbc.assignment.get("student_state"));
        assertThrows(RuntimeException.class, () -> service.complete("student-1", "complete-1"));
    }

    @Test
    void movesToTheRoundQuizOnlyAfterTheBottomIsRecorded() throws Exception {
        NoAiJdbcTemplate jdbc = new NoAiJdbcTemplate();
        ExperimentContentService content = mock(ExperimentContentService.class);
        when(content.contentForAssignment("assignment-1")).thenReturn(new ObjectMapper().readTree("""
                {"noAiLectures":{"M1":"固定讲义"}}
                """));
        NoAiLearningService service = new NoAiLearningService(jdbc, content, mock(ExperimentEventService.class));

        service.loadLecture("student-1");
        service.recordScroll("student-1", 100);
        service.complete("student-1", "complete-1");

        assertEquals("WAITING_ROUND_1_QUIZ", jdbc.assignment.get("student_state"));
        assertEquals("complete-1", jdbc.run.get("completion_idempotency_key"));
    }

    @Test
    void rejectsScrollEvidenceBeforeTheNoAiLectureHasStarted() {
        NoAiJdbcTemplate jdbc = new NoAiJdbcTemplate();
        NoAiLearningService service = new NoAiLearningService(jdbc, mock(ExperimentContentService.class),
                mock(ExperimentEventService.class));

        assertThrows(RuntimeException.class, () -> service.recordScroll("student-1", 100));
    }

    private static final class NoAiJdbcTemplate extends JdbcTemplate {
        private final Map<String, Object> assignment = assignment();
        private final Map<String, Object> run = new HashMap<>();

        private static Map<String, Object> assignment() {
            Map<String, Object> row = new HashMap<>();
            row.put("id", "assignment-1");
            row.put("participation_status", "ACTIVE");
            row.put("selected_mode", "NO_AI");
            row.put("student_state", "WAITING_ROUND_1_TUTOR");
            row.put("current_round", 1);
            row.put("content_version", "unsupervised-learning-v1");
            return row;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("t_student_experiment_assignment") && sql.contains("FOR UPDATE")) return List.of(assignment);
            if (sql.contains("t_no_ai_learning_run")) return run.isEmpty() ? List.of() : List.of(run);
            throw new AssertionError("未预期查询: " + sql);
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("INSERT INTO t_no_ai_learning_run")) {
                run.put("assignment_id", args[0]);
                run.put("round_no", args[1]);
                run.put("content_version", args[2]);
                run.put("max_scroll_percent", 0);
            } else if (sql.contains("max_scroll_percent")) {
                run.put("max_scroll_percent", args[0]);
                if (((Number) args[1]).doubleValue() >= 100) run.put("bottom_reached_at", "recorded");
            } else if (sql.contains("completion_idempotency_key")) {
                run.put("completion_idempotency_key", args[0]);
                run.put("completed_at", "recorded");
            } else if (sql.contains("UPDATE t_student_experiment_assignment SET student_state=?")) {
                assignment.put("student_state", args[0]);
            }
            return 1;
        }
    }
}
