package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.modules.experiment.domain.GlobalStage;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class ContentAssessmentService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ExperimentContentService content;
    private final ExperimentAccessService access;
    private final ExperimentEventService events;

    @Transactional
    ExperimentDtos.AssessmentVO start(String userId, Map<String, Object> assignment, GlobalStage stage) {
        access.requireStage(userId, stage);
        ExperimentContentService.AssessmentDefinition definition = definition(assignment, assessmentCode(stage));
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT id,status FROM t_content_assessment_attempt " +
                        "WHERE assignment_id=? AND assessment_code=?", assignment.get("id"), definition.code());
        if (!existing.isEmpty()) {
            if (!"IN_PROGRESS".equals(existing.get(0).get("status"))) throw new BusinessException(ResultCode.EXPERIMENT_ALREADY_SUBMITTED);
            ensureBeforeDeadline(String.valueOf(existing.get(0).get("id")));
            events.recordSystem(userId, "SESSION_RESUMED", Map.of("kind", "ASSESSMENT", "attemptId", existing.get(0).get("id")));
            return load(userId, String.valueOf(existing.get(0).get("id")));
        }

        String attemptId = UUID.randomUUID().toString();
        OffsetDateTime deadline = now().plusSeconds(definition.durationSeconds());
        jdbc.update("INSERT INTO t_content_assessment_attempt(id,assignment_id,content_version,assessment_code,status,started_at,deadline_at) " +
                        "VALUES(?,?,?,?,'IN_PROGRESS',NOW(),?)", attemptId, assignment.get("id"), assignment.get("content_version"),
                definition.code(), deadline);
        int changed = jdbc.update("UPDATE t_student_experiment_assignment SET student_state=?,last_activity_at=NOW(),updated_at=NOW(),version=version+1 " +
                        "WHERE id=? AND student_state=?", inProgressState(stage), assignment.get("id"), waitingState(stage));
        if (changed != 1) throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT);
        events.recordSystem(userId, "ASSESSMENT_STARTED", Map.of("attemptId", attemptId, "assessment", definition.code()));
        return load(userId, attemptId);
    }

    boolean ownsAttempt(String userId, String attemptId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM t_content_assessment_attempt ca " +
                "JOIN t_student_experiment_assignment a ON a.id=ca.assignment_id WHERE ca.id=? AND a.user_id=?", Integer.class, attemptId, userId);
        return count != null && count > 0;
    }

    ExperimentDtos.AssessmentVO load(String userId, String attemptId) {
        Map<String, Object> attempt = ownedAttempt(userId, attemptId, false);
        ensureBeforeDeadline(attemptId);
        ExperimentContentService.AssessmentDefinition definition = definition(attempt, string(attempt, "assessment_code"));
        List<ExperimentDtos.SavedAnswerVO> answers = jdbc.query("SELECT question_code,answer,revision,change_count FROM t_content_assessment_response WHERE attempt_id=?",
                (rs, n) -> new ExperimentDtos.SavedAnswerVO(rs.getString("question_code"), read(rs.getString("answer")),
                        rs.getInt("revision"), rs.getInt("change_count")), attemptId);
        return new ExperimentDtos.AssessmentVO(attemptId, definition.code(), definition.title(), "IN_PROGRESS",
                offset(attempt.get("started_at")), offset(attempt.get("deadline_at")), studentQuestions(definition), answers);
    }

    @Transactional
    void viewed(String userId, String attemptId, ExperimentDtos.QuestionViewedReq req) {
        Map<String, Object> attempt = ownedOpenAttempt(userId, attemptId);
        ensureQuestion(attempt, req.questionId());
        jdbc.update("INSERT INTO t_content_assessment_response(id,attempt_id,question_code,first_viewed_at) VALUES(?,?,?,NOW()) " +
                        "ON CONFLICT(attempt_id,question_code) DO UPDATE SET first_viewed_at=COALESCE(t_content_assessment_response.first_viewed_at,NOW())",
                UUID.randomUUID().toString(), attemptId, req.questionId());
        events.recordUserAction(userId, "QUESTION_VIEWED", Map.of("attemptId", attemptId), req.clientTimestamp(), req.questionId(), null);
    }

    @Transactional
    ExperimentDtos.SavedAnswerVO save(String userId, String attemptId, ExperimentDtos.AnswerSaveReq req) {
        Map<String, Object> attempt = ownedOpenAttempt(userId, attemptId);
        ensureQuestion(attempt, req.questionId());
        String answer = json(req.answer());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT answer,revision,change_count,last_idempotency_key FROM t_content_assessment_response " +
                "WHERE attempt_id=? AND question_code=? FOR UPDATE", attemptId, req.questionId());
        int revision;
        int changes;
        boolean changed;
        if (rows.isEmpty()) {
            if (req.revision() != 0) throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "答案版本已变化，请刷新");
            jdbc.update("INSERT INTO t_content_assessment_response(id,attempt_id,question_code,answer,revision,first_viewed_at,first_answered_at,last_answered_at,last_idempotency_key) " +
                            "VALUES(?,?,?,?::jsonb,1,NOW(),NOW(),NOW(),?)", UUID.randomUUID().toString(), attemptId,
                    req.questionId(), answer, req.idempotencyKey());
            revision = 1;
            changes = 0;
            changed = false;
        } else {
            Map<String, Object> current = rows.get(0);
            if (Objects.equals(current.get("last_idempotency_key"), req.idempotencyKey())) {
                return new ExperimentDtos.SavedAnswerVO(req.questionId(), read(String.valueOf(current.get("answer"))),
                        ((Number) current.get("revision")).intValue(), ((Number) current.get("change_count")).intValue());
            }
            int oldRevision = ((Number) current.get("revision")).intValue();
            if (oldRevision != req.revision()) throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "答案版本已变化，请刷新");
            changed = !Objects.equals(normalize(String.valueOf(current.get("answer"))), normalize(answer));
            changes = ((Number) current.get("change_count")).intValue() + (changed ? 1 : 0);
            revision = oldRevision + 1;
            jdbc.update("UPDATE t_content_assessment_response SET answer=?::jsonb,revision=?,first_answered_at=COALESCE(first_answered_at,NOW())," +
                            "last_answered_at=NOW(),change_count=?,answer_changed=answer_changed OR ?,last_idempotency_key=? WHERE attempt_id=? AND question_code=?",
                    answer, revision, changes, changed, req.idempotencyKey(), attemptId, req.questionId());
        }
        events.recordUserAction(userId, changed ? "ANSWER_CHANGED" : "ANSWER_SELECTED", Map.of("attemptId", attemptId, "revision", revision),
                req.clientTimestamp(), req.questionId(), null);
        jdbc.update("UPDATE t_student_experiment_assignment SET last_activity_at=NOW() WHERE id=?", attempt.get("assignment_id"));
        return new ExperimentDtos.SavedAnswerVO(req.questionId(), req.answer(), revision, changes);
    }

    @Transactional
    void submit(String userId, String attemptId, ExperimentDtos.SubmitAssessmentReq req) {
        Map<String, Object> attempt = ownedAttempt(userId, attemptId, false);
        GlobalStage stage = stageForAssessmentCode(string(attempt, "assessment_code"));
        access.requireStage(userId, stage);
        ensureBeforeDeadline(attemptId);
        doSubmit(attemptId, "SUBMITTED", req.idempotencyKey());
        events.recordSystem(userId, "ASSESSMENT_SUBMITTED", Map.of("attemptId", attemptId));
    }

    @Scheduled(fixedDelay = 15_000)
    public void submitExpired() {
        List<String> ids = jdbc.query("SELECT id FROM t_content_assessment_attempt WHERE status='IN_PROGRESS' AND deadline_at<=NOW() LIMIT 100",
                (rs, n) -> rs.getString(1));
        for (String id : ids) {
            try { submitExpiredOne(id); } catch (Exception ignored) { }
        }
    }

    @Transactional
    void submitExpiredOne(String attemptId) {
        List<Map<String, Object>> attempts = jdbc.queryForList("SELECT ca.id,a.user_id FROM t_content_assessment_attempt ca " +
                "JOIN t_student_experiment_assignment a ON a.id=ca.assignment_id WHERE ca.id=?", attemptId);
        if (attempts.isEmpty()) return;
        doSubmit(attemptId, "AUTO_SUBMITTED", "deadline:" + attemptId);
        events.recordSystem(String.valueOf(attempts.get(0).get("user_id")), "ASSESSMENT_AUTO_SUBMITTED", Map.of("attemptId", attemptId));
    }

    private void doSubmit(String attemptId, String status, String key) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT ca.*,a.user_id,a.student_state FROM t_content_assessment_attempt ca " +
                "JOIN t_student_experiment_assignment a ON a.id=ca.assignment_id WHERE ca.id=? FOR UPDATE OF ca", attemptId);
        if (rows.isEmpty()) throw new BusinessException(ResultCode.NOT_FOUND);
        Map<String, Object> attempt = rows.get(0);
        if (!"IN_PROGRESS".equals(attempt.get("status"))) {
            if (Objects.equals(attempt.get("submit_idempotency_key"), key)) return;
            throw new BusinessException(ResultCode.EXPERIMENT_ALREADY_SUBMITTED);
        }
        ExperimentContentService.AssessmentDefinition definition = definition(attempt, string(attempt, "assessment_code"));
        List<Map<String, Object>> responses = jdbc.queryForList("SELECT * FROM t_content_assessment_response WHERE attempt_id=?", attemptId);
        BigDecimal score = BigDecimal.ZERO;
        for (JsonNode item : definition.items()) {
            String questionCode = item.path("code").asText();
            Map<String, Object> response = responses.stream().filter(row -> questionCode.equals(row.get("question_code"))).findFirst().orElse(null);
            JsonNode answer = response == null ? null : read(String.valueOf(response.get("answer")));
            boolean correct = answer != null && answer.equals(item.path("answerKey"));
            if (correct) score = score.add(BigDecimal.ONE);
            if (response == null) {
                jdbc.update("INSERT INTO t_content_assessment_response(id,attempt_id,question_code,answer,submitted_at,is_correct,score) VALUES(?,?,?,NULL,NOW(),?,?)",
                        UUID.randomUUID().toString(), attemptId, questionCode, false, BigDecimal.ZERO);
            } else {
                OffsetDateTime viewed = offset(response.get("first_viewed_at"));
                long duration = viewed == null ? 0 : Math.max(0, Duration.between(viewed, now()).toMillis());
                jdbc.update("UPDATE t_content_assessment_response SET submitted_at=NOW(),duration_ms=?,is_correct=?,score=? WHERE id=?",
                        duration, correct, correct ? BigDecimal.ONE : BigDecimal.ZERO, response.get("id"));
            }
        }
        jdbc.update("UPDATE t_content_assessment_attempt SET status=?,submitted_at=NOW(),final_score=?,max_score=?,submit_idempotency_key=?,version=version+1 " +
                        "WHERE id=? AND status='IN_PROGRESS'", status, score, BigDecimal.valueOf(definition.items().size()), key, attemptId);
        GlobalStage stage = stageForAssessmentCode(definition.code());
        jdbc.update("UPDATE t_student_experiment_assignment SET student_state=?,current_round=?,last_activity_at=NOW(),updated_at=NOW(),version=version+1 WHERE id=?",
                AssessmentService.nextStateForCompletedAssessment(stage).name(), nextRoundFor(stage), attempt.get("assignment_id"));
    }

    private Map<String, Object> ownedOpenAttempt(String userId, String attemptId) {
        Map<String, Object> attempt = ownedAttempt(userId, attemptId, false);
        ensureBeforeDeadline(attemptId);
        access.requireStage(userId, stageForAssessmentCode(string(attempt, "assessment_code")));
        return attempt;
    }

    private Map<String, Object> ownedAttempt(String userId, String attemptId, boolean forUpdate) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT ca.*,a.user_id FROM t_content_assessment_attempt ca " +
                "JOIN t_student_experiment_assignment a ON a.id=ca.assignment_id WHERE ca.id=? AND a.user_id=?", attemptId, userId);
        if (rows.isEmpty()) throw new BusinessException(ResultCode.FORBIDDEN, "不能访问其他学生的测验");
        Map<String, Object> attempt = rows.get(0);
        if (!"IN_PROGRESS".equals(attempt.get("status"))) throw new BusinessException(ResultCode.EXPERIMENT_ALREADY_SUBMITTED, "该测试已提交并锁定");
        return attempt;
    }

    private void ensureBeforeDeadline(String attemptId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT deadline_at,status FROM t_content_assessment_attempt WHERE id=?", attemptId);
        if (!rows.isEmpty() && "IN_PROGRESS".equals(rows.get(0).get("status"))) {
            OffsetDateTime deadline = offset(rows.get(0).get("deadline_at"));
            if (deadline != null && !deadline.isAfter(now())) {
                submitExpiredOne(attemptId);
                throw new BusinessException(ResultCode.EXPERIMENT_DEADLINE_PASSED);
            }
        }
    }

    private ExperimentContentService.AssessmentDefinition definition(Map<String, Object> attemptOrAssignment, String assessmentCode) {
        return content.assessmentDefinition(content.contentForAssignment(String.valueOf(attemptOrAssignment.get("assignment_id") == null
                ? attemptOrAssignment.get("id") : attemptOrAssignment.get("assignment_id"))), assessmentCode);
    }

    private void ensureQuestion(Map<String, Object> attempt, String questionCode) {
        boolean found = false;
        for (JsonNode item : definition(attempt, string(attempt, "assessment_code")).items()) {
            if (questionCode.equals(item.path("code").asText())) {
                found = true;
                break;
            }
        }
        if (!found) throw new BusinessException(ResultCode.FORBIDDEN, "题目不属于当前测试");
    }

    static List<ExperimentDtos.QuestionVO> studentQuestions(ExperimentContentService.AssessmentDefinition definition) {
        List<ExperimentDtos.QuestionVO> questions = new ArrayList<>();
        int sequence = 1;
        for (JsonNode item : definition.items()) {
            String code = item.path("code").asText();
            questions.add(new ExperimentDtos.QuestionVO(code, code, "SINGLE_CHOICE", item.path("prompt").asText(),
                    item.path("options").deepCopy(), null, sequence++));
        }
        return questions;
    }

    private static String assessmentCode(GlobalStage stage) {
        if (stage == GlobalStage.PRETEST) return "PRETEST";
        if (stage == GlobalStage.POSTTEST) return "POSTTEST";
        if (stage.quiz()) return "Q" + stage.round();
        throw new BusinessException(ResultCode.EXPERIMENT_STAGE_LOCKED);
    }

    private static GlobalStage stageForAssessmentCode(String code) { return switch (code) {
        case "PRETEST" -> GlobalStage.PRETEST;
        case "Q1" -> GlobalStage.ROUND_1_QUIZ;
        case "Q2" -> GlobalStage.ROUND_2_QUIZ;
        case "Q3" -> GlobalStage.ROUND_3_QUIZ;
        case "Q4" -> GlobalStage.ROUND_4_QUIZ;
        case "Q5" -> GlobalStage.ROUND_5_QUIZ;
        case "POSTTEST" -> GlobalStage.POSTTEST;
        default -> throw new BusinessException(ResultCode.EXPERIMENT_STAGE_LOCKED);
    }; }

    static int nextRoundFor(GlobalStage stage) { return switch (stage) {
        case PRETEST -> 0;
        case ROUND_1_QUIZ -> 2;
        case ROUND_2_QUIZ -> 3;
        case ROUND_3_QUIZ -> 4;
        case ROUND_4_QUIZ, ROUND_5_QUIZ, POSTTEST -> 5;
        default -> throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT);
    }; }
    private static String waitingState(GlobalStage stage) { return "WAITING_" + stage.name(); }
    private static String inProgressState(GlobalStage stage) { return stage.name() + "_IN_PROGRESS"; }
    private JsonNode read(String value) { try { return value == null || "null".equals(value) ? null : objectMapper.readTree(value); } catch (Exception e) { return null; } }
    private String json(Object value) { try { return value == null ? "null" : objectMapper.writeValueAsString(value); } catch (Exception e) { throw new BusinessException(ResultCode.PARAM_ERROR); } }
    private static String normalize(String value) { return value == null ? "null" : value.replaceAll("\\s", ""); }
    private static String string(Map<String, Object> row, String key) { Object value = row.get(key); return value == null ? null : String.valueOf(value); }
    private static OffsetDateTime offset(Object value) {
        if (value instanceof OffsetDateTime dateTime) return dateTime;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        if (value instanceof java.time.Instant instant) return instant.atOffset(ZoneOffset.UTC);
        return null;
    }
    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
}
