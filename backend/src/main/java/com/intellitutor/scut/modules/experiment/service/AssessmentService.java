package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.modules.experiment.domain.GlobalStage;
import com.intellitutor.scut.modules.experiment.domain.StudentState;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AssessmentService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ExperimentAccessService access;
    private final ExperimentOrchestrationService orchestration;
    private final ExperimentEventService events;
    private final ContentAssessmentService contentAssessments;

    @Transactional
    public ExperimentDtos.AssessmentVO start(String userId) {
        Map<String, Object> assignment = orchestration.assignment(userId);
        GlobalStage stage = assessmentStageFor(StudentState.valueOf(string(assignment, "student_state")));
        requireAssessmentStage(stage);
        if (assignment.get("content_version") != null) {
            return contentAssessments.start(userId, assignment, stage);
        }
        access.requireStage(userId, stage);
        Map<String, Object> config = assessmentConfig(assignment, stage);
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT id,status FROM t_assessment_attempt WHERE assignment_id=? AND stage_id=?",
                assignment.get("id"), config.get("stage_id"));
        if (!existing.isEmpty()) {
            if (!"IN_PROGRESS".equals(existing.get(0).get("status"))) throw new BusinessException(ResultCode.EXPERIMENT_ALREADY_SUBMITTED);
            checkDeadline(String.valueOf(existing.get(0).get("id")));
            events.recordSystem(userId, "SESSION_RESUMED", Map.of("kind", "ASSESSMENT", "attemptId", existing.get(0).get("id")));
            return load(userId, String.valueOf(existing.get(0).get("id")));
        }

        String attemptId = UUID.randomUUID().toString();
        Integer seconds = config.get("duration_seconds") instanceof Number n ? n.intValue() : null;
        OffsetDateTime deadline = seconds == null ? null : now().plusSeconds(seconds);
        jdbc.update("INSERT INTO t_assessment_attempt(id,assignment_id,stage_id,assessment_id,status,started_at,deadline_at) " +
                        "VALUES(?,?,?,?,'IN_PROGRESS',NOW(),?)", attemptId, assignment.get("id"), config.get("stage_id"),
                config.get("assessment_id"), deadline);
        jdbc.update("INSERT INTO t_student_stage_progress(id,assignment_id,stage_id,status,started_at,last_heartbeat_at) " +
                        "VALUES(?,?,?,'IN_PROGRESS',NOW(),NOW()) ON CONFLICT(assignment_id,stage_id) DO UPDATE SET " +
                        "status='IN_PROGRESS',started_at=COALESCE(t_student_stage_progress.started_at,NOW()),last_heartbeat_at=NOW()",
                UUID.randomUUID().toString(), assignment.get("id"), config.get("stage_id"));
        String inProgressState = inProgressState(stage).name();
        int changed = jdbc.update("UPDATE t_student_experiment_assignment SET student_state=?,last_activity_at=NOW(),updated_at=NOW(),version=version+1 " +
                "WHERE id=? AND student_state=?", inProgressState, assignment.get("id"), waitingState(stage).name());
        if (changed != 1) throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT);
        events.recordSystem(userId, "ASSESSMENT_STARTED", Map.of("attemptId", attemptId, "assessment", config.get("code")));
        return load(userId, attemptId);
    }

    public ExperimentDtos.AssessmentVO load(String userId, String attemptId) {
        if (contentAssessments.ownsAttempt(userId, attemptId)) {
            return contentAssessments.load(userId, attemptId);
        }
        List<Map<String, Object>> attempts = jdbc.queryForList("SELECT aa.*,a.code,a.title FROM t_assessment_attempt aa " +
                "JOIN t_student_experiment_assignment x ON x.id=aa.assignment_id JOIN t_assessment a ON a.id=aa.assessment_id " +
                "WHERE aa.id=? AND x.user_id=?", attemptId, userId);
        if (attempts.isEmpty()) throw new BusinessException(ResultCode.FORBIDDEN, "不能访问其他学生的测验");
        Map<String, Object> attempt = attempts.get(0);
        if (!"IN_PROGRESS".equals(attempt.get("status"))) {
            throw new BusinessException(ResultCode.EXPERIMENT_ALREADY_SUBMITTED, "该测试已提交并锁定");
        }
        if ("IN_PROGRESS".equals(attempt.get("status"))) checkDeadline(attemptId);
        List<ExperimentDtos.QuestionVO> questions = jdbc.query("SELECT q.id,q.code,q.question_type,q.prompt,q.options,q.images,aq.sequence_no " +
                        "FROM t_assessment_question aq JOIN t_experiment_question q ON q.id=aq.question_id " +
                        "WHERE aq.assessment_id=? ORDER BY aq.sequence_no",
                (rs, n) -> new ExperimentDtos.QuestionVO(rs.getString("id"), rs.getString("code"), rs.getString("question_type"),
                        rs.getString("prompt"), read(rs.getString("options")), read(rs.getString("images")), rs.getInt("sequence_no")),
                attempt.get("assessment_id"));
        List<ExperimentDtos.SavedAnswerVO> answers = jdbc.query("SELECT question_id,answer,revision,change_count FROM t_assessment_response WHERE attempt_id=?",
                (rs, n) -> new ExperimentDtos.SavedAnswerVO(rs.getString("question_id"), read(rs.getString("answer")),
                        rs.getInt("revision"), rs.getInt("change_count")), attemptId);
        return new ExperimentDtos.AssessmentVO(attemptId, string(attempt, "code"), string(attempt, "title"), string(attempt, "status"),
                offset(attempt.get("started_at")), offset(attempt.get("deadline_at")), questions, answers);
    }

    @Transactional
    public void viewed(String userId, String attemptId, ExperimentDtos.QuestionViewedReq req) {
        if (contentAssessments.ownsAttempt(userId, attemptId)) {
            contentAssessments.viewed(userId, attemptId, req);
            return;
        }
        requireOpenOwned(userId, attemptId);
        ensureQuestion(attemptId, req.questionId());
        jdbc.update("INSERT INTO t_assessment_response(id,attempt_id,question_id,first_viewed_at) VALUES(?,?,?,NOW()) " +
                "ON CONFLICT(attempt_id,question_id) DO UPDATE SET first_viewed_at=COALESCE(t_assessment_response.first_viewed_at,NOW())",
                UUID.randomUUID().toString(), attemptId, req.questionId());
        events.recordUserAction(userId, "QUESTION_VIEWED", Map.of("attemptId", attemptId),
                req.clientTimestamp(), req.questionId(), null);
    }

    @Transactional
    public ExperimentDtos.SavedAnswerVO save(String userId, String attemptId, ExperimentDtos.AnswerSaveReq req) {
        if (contentAssessments.ownsAttempt(userId, attemptId)) {
            return contentAssessments.save(userId, attemptId, req);
        }
        requireOpenOwned(userId, attemptId);
        ensureQuestion(attemptId, req.questionId());
        String answer = json(req.answer());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT answer,revision,change_count,last_idempotency_key FROM t_assessment_response " +
                "WHERE attempt_id=? AND question_id=? FOR UPDATE", attemptId, req.questionId());
        int revision;
        int changes;
        boolean changed;
        if (rows.isEmpty()) {
            if (req.revision() != 0) throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "答案版本已变化，请刷新");
            jdbc.update("INSERT INTO t_assessment_response(id,attempt_id,question_id,answer,revision,first_viewed_at,first_answered_at,last_answered_at,last_idempotency_key) " +
                            "VALUES(?,?,?,?::jsonb,1,NOW(),NOW(),NOW(),?)", UUID.randomUUID().toString(), attemptId,
                    req.questionId(), answer, req.idempotencyKey());
            revision = 1; changes = 0; changed = false;
            events.recordUserAction(userId, "ANSWER_SELECTED", Map.of("attemptId", attemptId, "revision", revision),
                    req.clientTimestamp(), req.questionId(), null);
        } else {
            Map<String, Object> current = rows.get(0);
            if (Objects.equals(current.get("last_idempotency_key"), req.idempotencyKey())) {
                return new ExperimentDtos.SavedAnswerVO(req.questionId(), read(String.valueOf(current.get("answer"))),
                        ((Number) current.get("revision")).intValue(), ((Number) current.get("change_count")).intValue());
            }
            int oldRevision = ((Number) current.get("revision")).intValue();
            if (oldRevision != req.revision()) throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "答案版本已变化，请刷新");
            String previous = String.valueOf(current.get("answer"));
            changed = !Objects.equals(normalize(previous), normalize(answer));
            changes = ((Number) current.get("change_count")).intValue() + (changed ? 1 : 0);
            revision = oldRevision + 1;
            jdbc.update("UPDATE t_assessment_response SET answer=?::jsonb,revision=?,first_answered_at=COALESCE(first_answered_at,NOW())," +
                            "last_answered_at=NOW(),change_count=?,answer_changed=answer_changed OR ?,last_idempotency_key=? WHERE attempt_id=? AND question_id=?",
                    answer, revision, changes, changed, req.idempotencyKey(), attemptId, req.questionId());
            events.recordUserAction(userId, changed ? "ANSWER_CHANGED" : "ANSWER_SELECTED",
                    Map.of("attemptId", attemptId, "revision", revision), req.clientTimestamp(), req.questionId(), null);
        }
        jdbc.update("UPDATE t_student_experiment_assignment SET last_activity_at=NOW() WHERE id=(SELECT assignment_id FROM t_assessment_attempt WHERE id=?)", attemptId);
        return new ExperimentDtos.SavedAnswerVO(req.questionId(), req.answer(), revision, changes);
    }

    @Transactional
    public void submit(String userId, String attemptId, ExperimentDtos.SubmitAssessmentReq req) {
        if (contentAssessments.ownsAttempt(userId, attemptId)) {
            contentAssessments.submit(userId, attemptId, req);
            return;
        }
        Map<String, Object> attempt = ownedAttempt(userId, attemptId);
        if (!"IN_PROGRESS".equals(attempt.get("status"))) {
            if (Objects.equals(attempt.get("submit_idempotency_key"), req.idempotencyKey())) return;
            throw new BusinessException(ResultCode.EXPERIMENT_ALREADY_SUBMITTED);
        }
        GlobalStage stage = GlobalStage.valueOf(string(attempt, "stage_code"));
        access.requireStage(userId, stage);
        checkDeadline(attemptId);
        doSubmit(attemptId, "SUBMITTED", req.idempotencyKey());
        recordQuestionSubmissions(userId, attemptId);
        events.recordSystem(userId, "ASSESSMENT_SUBMITTED", Map.of("attemptId", attemptId));
    }

    @Scheduled(fixedDelay = 15_000)
    public void submitExpired() {
        List<String> ids = jdbc.query("SELECT id FROM t_assessment_attempt WHERE status='IN_PROGRESS' AND deadline_at IS NOT NULL AND deadline_at<=NOW() LIMIT 100",
                (rs, n) -> rs.getString(1));
        for (String id : ids) {
            try { submitExpiredOne(id); } catch (Exception ignored) { }
        }
    }

    @Transactional
    public void submitExpiredOne(String attemptId) {
        List<String> users = jdbc.query("SELECT a.user_id FROM t_assessment_attempt aa JOIN t_student_experiment_assignment a ON a.id=aa.assignment_id WHERE aa.id=?",
                (rs,n) -> rs.getString(1), attemptId);
        if (users.isEmpty()) return;
        doSubmit(attemptId, "AUTO_SUBMITTED", "deadline:" + attemptId);
        recordQuestionSubmissions(users.get(0), attemptId);
        events.recordSystem(users.get(0), "ASSESSMENT_AUTO_SUBMITTED", Map.of("attemptId", attemptId));
    }

    private void recordQuestionSubmissions(String userId, String attemptId) {
        List<Map<String, Object>> responses = jdbc.queryForList("SELECT q.id question_id,r.answer IS NOT NULL answered,r.revision,r.change_count," +
                "r.is_correct,r.score,r.duration_ms FROM t_assessment_attempt aa JOIN t_assessment_question aq ON aq.assessment_id=aa.assessment_id " +
                "JOIN t_experiment_question q ON q.id=aq.question_id LEFT JOIN t_assessment_response r ON r.attempt_id=aa.id AND r.question_id=q.id " +
                "WHERE aa.id=? ORDER BY aq.sequence_no", attemptId);
        for (Map<String, Object> response : responses) {
            String questionId = String.valueOf(response.remove("question_id"));
            events.recordUserAction(userId, "QUESTION_SUBMITTED", response, null, questionId, null);
        }
    }

    private void doSubmit(String attemptId, String status, String key) {
        List<Map<String, Object>> attempts = jdbc.queryForList("SELECT aa.*,s.stage_code,s.id progress_stage_id,a.assessment_type " +
                "FROM t_assessment_attempt aa JOIN t_experiment_stage s ON s.id=aa.stage_id JOIN t_assessment a ON a.id=aa.assessment_id " +
                "WHERE aa.id=? FOR UPDATE OF aa", attemptId);
        if (attempts.isEmpty()) throw new BusinessException(ResultCode.NOT_FOUND);
        Map<String, Object> attempt = attempts.get(0);
        if (!"IN_PROGRESS".equals(attempt.get("status"))) {
            if (Objects.equals(attempt.get("submit_idempotency_key"), key)) return;
            throw new BusinessException(ResultCode.EXPERIMENT_ALREADY_SUBMITTED);
        }
        List<Map<String, Object>> answers = jdbc.queryForList("SELECT r.id,r.answer,r.first_viewed_at,q.question_type,q.answer_key,q.numeric_tolerance,aq.points " +
                "FROM t_assessment_question aq JOIN t_experiment_question q ON q.id=aq.question_id " +
                "LEFT JOIN t_assessment_response r ON r.question_id=q.id AND r.attempt_id=? WHERE aq.assessment_id=?", attemptId, attempt.get("assessment_id"));
        BigDecimal total = BigDecimal.ZERO, score = BigDecimal.ZERO;
        boolean scored = !"QUESTIONNAIRE".equals(attempt.get("assessment_type"));
        for (Map<String, Object> answer : answers) {
            BigDecimal points = (BigDecimal) answer.get("points"); total = total.add(points);
            Boolean correct = scored ? isCorrect(answer) : null;
            BigDecimal itemScore = Boolean.TRUE.equals(correct) ? points : BigDecimal.ZERO;
            if (scored) score = score.add(itemScore);
            if (answer.get("id") != null) {
                OffsetDateTime viewed = offset(answer.get("first_viewed_at"));
                long duration = viewed == null ? 0 : Math.max(0, Duration.between(viewed, now()).toMillis());
                jdbc.update("UPDATE t_assessment_response SET submitted_at=NOW(),duration_ms=?,is_correct=?,score=? WHERE id=?",
                        duration, correct, scored ? itemScore : null, answer.get("id"));
            }
        }
        jdbc.update("UPDATE t_assessment_attempt SET status=?,submitted_at=NOW(),final_score=?,max_score=?,submit_idempotency_key=?,version=version+1 WHERE id=? AND status='IN_PROGRESS'",
                status, scored ? score : null, scored ? total : null, key, attemptId);
        jdbc.update("UPDATE t_student_stage_progress SET status='COMPLETED',completed_at=NOW(),last_heartbeat_at=NOW(),version=version+1 " +
                "WHERE assignment_id=? AND stage_id=?", attempt.get("assignment_id"), attempt.get("stage_id"));
        GlobalStage stage = GlobalStage.valueOf(string(attempt, "stage_code"));
        Transition transition = transition(stage);
        jdbc.update("UPDATE t_student_experiment_assignment SET student_state=?,current_round=?,last_activity_at=NOW()," +
                "completed_at=CASE WHEN ?='EXPERIMENT_FINISHED' THEN NOW() ELSE completed_at END,updated_at=NOW(),version=version+1 WHERE id=?",
                transition.state().name(), transition.round(), transition.state().name(), attempt.get("assignment_id"));
    }

    private Map<String, Object> requireOpenOwned(String userId, String attemptId) {
        Map<String, Object> row = ownedAttempt(userId, attemptId);
        if (!"IN_PROGRESS".equals(row.get("status"))) throw new BusinessException(ResultCode.EXPERIMENT_ALREADY_SUBMITTED);
        GlobalStage stage = GlobalStage.valueOf(string(row, "stage_code"));
        access.requireStage(userId, stage);
        checkDeadline(attemptId);
        return row;
    }

    private Map<String, Object> ownedAttempt(String userId, String attemptId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT aa.*,s.stage_code FROM t_assessment_attempt aa JOIN t_student_experiment_assignment a ON a.id=aa.assignment_id " +
                "JOIN t_experiment_stage s ON s.id=aa.stage_id JOIN t_experiment e ON e.id=a.experiment_id WHERE aa.id=? AND a.user_id=?", attemptId, userId);
        if (rows.isEmpty()) throw new BusinessException(ResultCode.FORBIDDEN);
        return rows.get(0);
    }

    private void checkDeadline(String attemptId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT deadline_at,status FROM t_assessment_attempt WHERE id=?", attemptId);
        if (!rows.isEmpty() && "IN_PROGRESS".equals(rows.get(0).get("status"))) {
            OffsetDateTime deadline = offset(rows.get(0).get("deadline_at"));
            if (deadline != null && !deadline.isAfter(now())) {
                submitExpiredOne(attemptId);
                throw new BusinessException(ResultCode.EXPERIMENT_DEADLINE_PASSED);
            }
        }
    }

    private Map<String, Object> assessmentConfig(Map<String, Object> assignment, GlobalStage stage) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT s.id stage_id,COALESCE(gc.assessment_id,s.assessment_id) assessment_id," +
                "COALESCE(a.duration_seconds,s.duration_seconds) duration_seconds,a.code FROM t_experiment_stage s " +
                "LEFT JOIN t_experiment_stage_group_config gc ON gc.stage_id=s.id AND gc.group_code=? " +
                "JOIN t_assessment a ON a.id=COALESCE(gc.assessment_id,s.assessment_id) WHERE s.experiment_id=? AND s.stage_code=?",
                assignment.get("group_code"), assignment.get("experiment_id"), stage.name());
        if (rows.isEmpty()) throw new BusinessException(ResultCode.NOT_FOUND, "当前阶段未配置 Assessment");
        return rows.get(0);
    }
    private void ensureQuestion(String attemptId, String questionId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM t_assessment_attempt aa JOIN t_assessment_question aq ON aq.assessment_id=aa.assessment_id " +
                "WHERE aa.id=? AND aq.question_id=?", Long.class, attemptId, questionId);
        if (count == null || count == 0) throw new BusinessException(ResultCode.FORBIDDEN, "题目不属于当前问卷");
    }
    private Boolean isCorrect(Map<String, Object> row) {
        JsonNode answer = read(row.get("answer") == null ? null : String.valueOf(row.get("answer")));
        JsonNode key = read(row.get("answer_key") == null ? null : String.valueOf(row.get("answer_key")));
        if (answer == null || key == null) return false;
        String type = string(row, "question_type");
        if ("MULTIPLE_CHOICE".equals(type)) {
            Set<String> a = new HashSet<>(), k = new HashSet<>(); answer.forEach(v -> a.add(v.asText())); key.forEach(v -> k.add(v.asText())); return a.equals(k);
        }
        if ("NUMERIC".equals(type)) {
            try {
                BigDecimal av = answer.decimalValue(), kv = key.decimalValue();
                BigDecimal tolerance = row.get("numeric_tolerance") instanceof BigDecimal b ? b : BigDecimal.ZERO;
                return av.subtract(kv).abs().compareTo(tolerance) <= 0;
            } catch (Exception e) { return false; }
        }
        return answer.equals(key);
    }

    private static StudentState waitingState(GlobalStage stage) { return StudentState.valueOf("WAITING_" + stage.name()); }
    private static StudentState inProgressState(GlobalStage stage) { return StudentState.valueOf(stage.name() + "_IN_PROGRESS"); }
    private static Transition transition(GlobalStage stage) {
        return new Transition(nextStateForCompletedAssessment(stage), switch (stage) {
            case ROUND_1_QUIZ -> 2;
            case ROUND_2_QUIZ -> 3;
            case ROUND_3_QUIZ -> 4;
            case ROUND_4_QUIZ, ROUND_5_QUIZ, POSTTEST, QUESTIONNAIRE -> 5;
            default -> 0;
        });
    }
    static StudentState nextStateForCompletedAssessment(GlobalStage stage) { return switch (stage) {
        case PRETEST -> StudentState.WAITING_MODE_SELECTION;
        case ROUND_1_QUIZ -> StudentState.WAITING_ROUND_2_TUTOR;
        case ROUND_2_QUIZ -> StudentState.WAITING_ROUND_3_TUTOR;
        case ROUND_3_QUIZ -> StudentState.WAITING_ROUND_4_TUTOR;
        case ROUND_4_QUIZ -> StudentState.WAITING_ROUND_5_TUTOR;
        case ROUND_5_QUIZ -> StudentState.WAITING_POSTTEST;
        case POSTTEST -> StudentState.WAITING_CREDENTIAL;
        case QUESTIONNAIRE -> StudentState.EXPERIMENT_FINISHED;
        default -> throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT);
    }; }
    static GlobalStage assessmentStageFor(StudentState state) { return switch (state) {
        case WAITING_PRETEST, PRETEST_IN_PROGRESS -> GlobalStage.PRETEST;
        case WAITING_ROUND_1_QUIZ, ROUND_1_QUIZ_IN_PROGRESS -> GlobalStage.ROUND_1_QUIZ;
        case WAITING_ROUND_2_QUIZ, ROUND_2_QUIZ_IN_PROGRESS -> GlobalStage.ROUND_2_QUIZ;
        case WAITING_ROUND_3_QUIZ, ROUND_3_QUIZ_IN_PROGRESS -> GlobalStage.ROUND_3_QUIZ;
        case WAITING_ROUND_4_QUIZ, ROUND_4_QUIZ_IN_PROGRESS -> GlobalStage.ROUND_4_QUIZ;
        case WAITING_ROUND_5_QUIZ, ROUND_5_QUIZ_IN_PROGRESS -> GlobalStage.ROUND_5_QUIZ;
        case WAITING_POSTTEST, POSTTEST_IN_PROGRESS -> GlobalStage.POSTTEST;
        case WAITING_QUESTIONNAIRE, QUESTIONNAIRE_IN_PROGRESS -> GlobalStage.QUESTIONNAIRE;
        default -> throw new BusinessException(ResultCode.EXPERIMENT_STAGE_LOCKED, "当前个人状态不能进行测试");
    }; }
    private static void requireAssessmentStage(GlobalStage stage) { if (!(stage == GlobalStage.PRETEST || stage.quiz() || stage == GlobalStage.POSTTEST || stage == GlobalStage.QUESTIONNAIRE)) throw new BusinessException(ResultCode.EXPERIMENT_STAGE_LOCKED); }
    private JsonNode read(String value) { try { return value == null || "null".equals(value) ? null : objectMapper.readTree(value); } catch (Exception e) { return null; } }
    private String json(Object value) { try { return value == null ? "null" : objectMapper.writeValueAsString(value); } catch (Exception e) { throw new BusinessException(ResultCode.PARAM_ERROR); } }
    private static String normalize(String value) { return value == null ? "null" : value.replaceAll("\\s", ""); }
    private static String string(Map<String, Object> row, String key) { Object value=row.get(key); return value==null?null:String.valueOf(value); }
    private static OffsetDateTime offset(Object value) {
        if (value instanceof OffsetDateTime odt) return odt;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        if (value instanceof java.time.Instant instant) return instant.atOffset(ZoneOffset.UTC);
        return null;
    }
    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    private record Transition(StudentState state, int round) {}
}
