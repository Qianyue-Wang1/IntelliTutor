package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NoAiLearningService {
    private final JdbcTemplate jdbc;
    private final ExperimentContentService content;
    private final ExperimentEventService events;

    @Transactional
    public ExperimentDtos.NoAiLectureVO loadLecture(String userId) {
        Map<String, Object> assignment = assignmentForUpdate(userId);
        int round = requireNoAiTutorState(assignment, true);
        ensureRun(assignment, round);
        if (waitingTutorState(round).equals(string(assignment, "student_state"))) {
            jdbc.update("UPDATE t_student_experiment_assignment SET student_state=?,last_activity_at=NOW(),updated_at=NOW() " +
                            "WHERE id=? AND student_state=?",
                    tutorInProgressState(round), string(assignment, "id"), waitingTutorState(round));
            events.recordSystem(userId, "NO_AI_LEARNING_STARTED", Map.of("round", round));
        }
        JsonNode lecture = content.contentForAssignment(string(assignment, "id"))
                .path("noAiLectures").path("M" + round);
        if (!lecture.isTextual() || lecture.asText().isBlank()) {
            throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "本轮无 AI 学习讲义尚未配置");
        }
        return new ExperimentDtos.NoAiLectureVO(round, lecture.asText(), runFor(assignment, round).get("bottom_reached_at") != null);
    }

    @Transactional
    public void recordScroll(String userId, double progress) {
        if (progress < 0 || progress > 100) throw new BusinessException(ResultCode.PARAM_ERROR, "滚动进度无效");
        Map<String, Object> assignment = assignmentForUpdate(userId);
        int round = requireNoAiTutorState(assignment, false);
        ensureRun(assignment, round);
        jdbc.update("UPDATE t_no_ai_learning_run SET max_scroll_percent=GREATEST(max_scroll_percent,?),last_activity_at=NOW()," +
                        "bottom_reached_at=CASE WHEN ? >= 100 THEN COALESCE(bottom_reached_at,NOW()) ELSE bottom_reached_at END " +
                        "WHERE assignment_id=? AND round_no=?",
                progress, progress, string(assignment, "id"), round);
        if (progress >= 100) events.recordSystem(userId, "NO_AI_BOTTOM_REACHED", Map.of("round", round));
    }

    @Transactional
    public void complete(String userId, String idempotencyKey) {
        Map<String, Object> assignment = assignmentForUpdate(userId);
        int round = requireNoAiTutorState(assignment, false);
        Map<String, Object> run = runFor(assignment, round);
        if (run.isEmpty() || run.get("bottom_reached_at") == null) {
            throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "请阅读至讲义底部后再进入小后测");
        }
        if (run.get("completed_at") != null) {
            if (idempotencyKey.equals(run.get("completion_idempotency_key"))) return;
            throw new BusinessException(ResultCode.EXPERIMENT_ALREADY_SUBMITTED);
        }
        jdbc.update("UPDATE t_no_ai_learning_run SET completed_at=NOW(),completion_idempotency_key=?,last_activity_at=NOW() " +
                        "WHERE assignment_id=? AND round_no=? AND completed_at IS NULL",
                idempotencyKey, string(assignment, "id"), round);
        jdbc.update("UPDATE t_student_experiment_assignment SET student_state=?,last_activity_at=NOW(),updated_at=NOW() " +
                        "WHERE id=? AND student_state=?",
                waitingQuizState(round), string(assignment, "id"), tutorInProgressState(round));
        events.recordSystem(userId, "NO_AI_LEARNING_COMPLETED", Map.of("round", round));
    }

    private Map<String, Object> assignmentForUpdate(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.id,a.participation_status,a.selected_mode,a.student_state," +
                        "a.current_round,a.content_version FROM t_student_experiment_assignment a WHERE a.user_id=? " +
                        "ORDER BY a.created_at DESC LIMIT 1 FOR UPDATE", userId);
        if (rows.isEmpty()) throw new BusinessException(ResultCode.EXPERIMENT_NOT_ASSIGNED);
        return rows.getFirst();
    }

    private int requireNoAiTutorState(Map<String, Object> assignment, boolean allowWaiting) {
        if (!"ACTIVE".equals(string(assignment, "participation_status")) ||
                !"NO_AI".equals(string(assignment, "selected_mode"))) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        int round = ((Number) assignment.get("current_round")).intValue();
        String state = string(assignment, "student_state");
        if (round < 1 || round > 5 || (!tutorInProgressState(round).equals(state) &&
                !(allowWaiting && waitingTutorState(round).equals(state)))) {
            throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT);
        }
        if (string(assignment, "content_version") == null) {
            throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT);
        }
        return round;
    }

    private void ensureRun(Map<String, Object> assignment, int round) {
        jdbc.update("INSERT INTO t_no_ai_learning_run(assignment_id,round_no,content_version,last_activity_at) VALUES(?,?,?,NOW()) " +
                        "ON CONFLICT(assignment_id,round_no) DO UPDATE SET last_activity_at=NOW()",
                string(assignment, "id"), round, string(assignment, "content_version"));
    }

    private Map<String, Object> runFor(Map<String, Object> assignment, int round) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT bottom_reached_at,completed_at,completion_idempotency_key " +
                        "FROM t_no_ai_learning_run WHERE assignment_id=? AND round_no=? FOR UPDATE",
                string(assignment, "id"), round);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private static String waitingTutorState(int round) { return "WAITING_ROUND_" + round + "_TUTOR"; }
    private static String tutorInProgressState(int round) { return "ROUND_" + round + "_TUTOR_IN_PROGRESS"; }
    private static String waitingQuizState(int round) { return "WAITING_ROUND_" + round + "_QUIZ"; }
    private static String string(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : String.valueOf(value);
    }
}
