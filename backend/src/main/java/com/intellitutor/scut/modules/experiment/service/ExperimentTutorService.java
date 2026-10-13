package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.modules.experiment.domain.ExperimentGroup;
import com.intellitutor.scut.modules.experiment.domain.GlobalStage;
import com.intellitutor.scut.modules.experiment.domain.StudentState;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import com.intellitutor.scut.modules.session.dto.req.DiscussionInitReq;
import com.intellitutor.scut.modules.session.dto.req.ExplanationInitReq;
import com.intellitutor.scut.modules.session.service.SessionService;
import com.intellitutor.scut.modules.session.service.PythonProxyService;
import com.intellitutor.scut.modules.session.entity.SessionRecord;
import com.intellitutor.scut.modules.task.dto.InferenceTaskMsg;
import com.intellitutor.scut.modules.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpMethod;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ExperimentTutorService {
    private static final String NEXT_STEP_RETRY = "重试进入下一步";
    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final TaskService tasks;
    private final ExperimentOrchestrationService orchestration;
    private final ExperimentAccessService access;
    private final ExperimentEventService events;
    private final ObjectMapper objectMapper;
    private final PythonProxyService pythonProxy;

    @Transactional
    public ExperimentDtos.TutorStartVO start(String userId, ExperimentDtos.TutorStartReq req) {
        Map<String, Object> assignment = lockedAssignment(userId);
        GlobalStage stage = tutorStage(StudentState.valueOf(string(assignment, "student_state")));
        if (!stage.tutor()) throw new BusinessException(ResultCode.EXPERIMENT_STAGE_LOCKED);
        access.requireStage(userId, stage);
        int round = stage.round();
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT * FROM t_experiment_tutor_run WHERE assignment_id=? AND round_no=?",
                assignment.get("id"), round);
        if (!existing.isEmpty()) {
            Map<String, Object> run = existing.get(0);
            Map<String, Object> session = jdbc.queryForMap("SELECT python_session_id FROM t_session WHERE id=?", run.get("session_id"));
            String latestTask = null;
            if (session.get("python_session_id") == null) {
                List<Map<String, Object>> taskRows = jdbc.queryForList("SELECT id,status FROM t_task WHERE session_id=? AND task_type LIKE '%_INIT' ORDER BY created_at DESC LIMIT 1",
                        run.get("session_id"));
                if (!taskRows.isEmpty() && !"failed".equals(taskRows.get(0).get("status"))) {
                    latestTask = string(taskRows.get(0), "id");
                } else {
                    ExperimentGroup existingGroup = ExperimentGroup.valueOf(string(run, "group_code_snapshot"));
                    String question = jdbc.queryForObject("SELECT prompt FROM t_experiment_question WHERE id=?", String.class, run.get("question_id"));
                    latestTask = publishInit(existingGroup, string(run, "mode"), userId, string(run, "id"),
                            string(run, "session_id"), question, ((Number) run.get("round_no")).intValue());
                    events.recordSystem(userId, "TUTOR_INIT_RETRIED", Map.of("runId", run.get("id"), "sessionId", run.get("session_id")));
                }
            }
            events.recordSystem(userId, "SESSION_RESUMED", Map.of("kind", "TUTOR", "runId", run.get("id")));
            return vo(run, latestTask, true);
        }

        ExperimentGroup group;
        try { group = ExperimentGroup.valueOf(selectedGroup(assignment)); }
        catch (Exception e) { throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "学生尚未分组"); }
        if (!group.participatesInTutor()) throw new BusinessException(ResultCode.FORBIDDEN, "Blank Control 不参与 Tutor");
        String mode;
        if (group == ExperimentGroup.DIRECT_LLM) mode = "direct";
        else {
            mode = req.mode() == null ? "" : req.mode().toLowerCase(Locale.ROOT);
            if (!Set.of("explanation", "discussion").contains(mode))
                throw new BusinessException(ResultCode.PARAM_ERROR, "本轮必须选择 explanation 或 discussion");
        }
        Map<String, Object> config = tutorConfig(assignment, stage, group);
        String runId = UUID.randomUUID().toString();
        TutorFeatureFlags featureFlags = tutorFeatureFlags(group, round);
        boolean historical = featureFlags.crossSessionEnabled();
        jdbc.update("INSERT INTO t_experiment_tutor_run(id,assignment_id,stage_id,round_no,group_code_snapshot,question_id,mode,status," +
                        "personalization_enabled,learner_state_status,started_at) VALUES(?,?,?,?,?,?,?,'NOT_STARTED',?,?,NOW())",
                runId, assignment.get("id"), config.get("stage_id"), round, group.name(), config.get("question_id"), mode,
                historical, group == ExperimentGroup.FULL_TUTOR ? "PENDING" : "NOT_APPLICABLE");
        String modelQuestion = modelQuestionForRun(runId, group, string(config, "prompt"), round);
        String sessionId = sessions.createExperimentSession(userId, mode, modelQuestion, runId);
        jdbc.update("UPDATE t_experiment_tutor_run SET session_id=?,status='IN_PROGRESS',version=version+1 WHERE id=? AND status='NOT_STARTED'", sessionId, runId);
        recordMessage(sessionId, "system", "ASSIGNED_QUESTION", modelQuestion, "assigned:" + runId, null);
        jdbc.update("INSERT INTO t_student_stage_progress(id,assignment_id,stage_id,status,started_at,last_heartbeat_at) " +
                        "VALUES(?,?,?,'IN_PROGRESS',NOW(),NOW()) ON CONFLICT(assignment_id,stage_id) DO UPDATE SET status='IN_PROGRESS'," +
                        "started_at=COALESCE(t_student_stage_progress.started_at,NOW()),last_heartbeat_at=NOW()",
                UUID.randomUUID().toString(), assignment.get("id"), config.get("stage_id"));
        StudentState inProgress = StudentState.valueOf(stage.name() + "_IN_PROGRESS");
        StudentState waiting = StudentState.valueOf("WAITING_" + stage.name());
        int changed = jdbc.update("UPDATE t_student_experiment_assignment SET student_state=?,current_round=?,last_activity_at=NOW()," +
                "updated_at=NOW(),version=version+1 WHERE id=? AND student_state=?", inProgress.name(), round, assignment.get("id"), waiting.name());
        if (changed != 1) throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT);

        String taskId = publishInit(group, mode, userId, runId, sessionId, string(config, "prompt"), round);
        events.recordSystem(userId, "TUTOR_STARTED", Map.of("runId", runId, "sessionId", sessionId, "mode", mode, "round", round));
        if (group.structured()) events.recordSystem(userId, "TUTOR_MODE_SELECTED", Map.of("runId", runId, "mode", mode, "round", round));
        if (historical) events.recordSystem(userId, "PERSONALIZATION_CONTEXT_REQUESTED",
                Map.of("runId", runId, "sourceRounds", round - 1, "policy", "FULL_TUTOR_CROSS_SESSION", "status", "REQUESTED"));
        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM t_experiment_tutor_run WHERE id=?", runId);
        return vo(run, taskId, false);
    }

    @Transactional
    public String directFollowup(String userId, String sessionId, ExperimentDtos.DirectFollowupReq req) {
        access.requireSessionAccess(userId, sessionId, "direct");
        Map<String, Object> run = jdbc.queryForMap("SELECT r.id,r.group_code_snapshot,r.interaction_count FROM t_experiment_tutor_run r " +
                "JOIN t_student_experiment_assignment a ON a.id=r.assignment_id WHERE r.session_id=? AND a.user_id=? FOR UPDATE OF r",
                sessionId, userId);
        if (!"DIRECT_LLM".equals(string(run, "group_code_snapshot"))) throw new BusinessException(ResultCode.FORBIDDEN);
        if (((Number) run.getOrDefault("interaction_count", 0)).intValue() >= 3) {
            throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "本轮追问次数已达上限，请进入小后测");
        }
        Map<String, Object> session = jdbc.queryForMap("SELECT python_session_id FROM t_session WHERE id=?", sessionId);
        if (session.get("python_session_id") == null) throw new BusinessException(ResultCode.SESSION_STATE_CONFLICT, "初始讲解尚未完成");
        int reserved = jdbc.update("UPDATE t_experiment_tutor_run SET interaction_count=interaction_count+1,version=version+1 " +
                "WHERE id=? AND interaction_count<3", run.get("id"));
        if (reserved != 1) throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "本轮追问次数已达上限，请进入小后测");
        recordMessage(sessionId, "student", "FOLLOWUP", req.message(), req.idempotencyKey(), null);
        events.recordSystem(userId, "TUTOR_MESSAGE_SENT", Map.of("sessionId", sessionId, "kind", "FOLLOWUP"));
        return tasks.publishDirectFollowup(sessionId, userId, Map.of("session_id", session.get("python_session_id"), "message", req.message()));
    }

    public void requireStructuredInteraction(String userId, String sessionId) {
        List<Map<String, Object>> runs = jdbc.queryForList("SELECT r.group_code_snapshot,r.interaction_count FROM t_experiment_tutor_run r " +
                        "JOIN t_student_experiment_assignment a ON a.id=r.assignment_id WHERE r.session_id=? AND a.user_id=?",
                sessionId, userId);
        if (runs.isEmpty()) return;
        Map<String, Object> run = runs.get(0);
        if (Set.of("BASIC_TUTOR", "FULL_TUTOR").contains(string(run, "group_code_snapshot"))
                && ((Number) run.getOrDefault("interaction_count", 0)).intValue() >= 30) {
            throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "本轮交互次数已达上限，请进入小后测");
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> directState(String userId, String sessionId) {
        access.requireSessionAccess(userId, sessionId, "direct");
        SessionRecord session = sessions.getSessionById(sessionId);
        if (session == null || !userId.equals(session.getUserId())) throw new BusinessException(ResultCode.FORBIDDEN);
        Map<String, Object> response = pythonProxy.proxyWithSessionRecovery(session, "direct",
                "/api/v1/direct/sessionstate/{pythonSessionId}", HttpMethod.GET, null);
        Object raw = response.get("data");
        if (!(raw instanceof Map<?, ?>)) throw new BusinessException(ResultCode.REMOTE_CALL_ERROR);
        Map<String, Object> data = new HashMap<>((Map<String, Object>) raw);
        data.remove("question");
        data.put("display_question", directDisplayQuestion(sessionId));
        data.put("session_id", sessionId);
        try { sessions.updateStateSnapshot(sessionId, objectMapper.writeValueAsString(raw)); } catch (Exception ignored) { }
        return data;
    }

    private String directDisplayQuestion(String sessionId) {
        try {
            Map<String, Object> row = jdbc.queryForMap("SELECT cv.package_json::text package_json,r.round_no FROM t_experiment_tutor_run r " +
                    "JOIN t_student_experiment_assignment a ON a.id=r.assignment_id " +
                    "JOIN t_experiment_content_version cv ON cv.experiment_id=a.experiment_id AND cv.version=a.content_version WHERE r.session_id=?", sessionId);
            String topic = objectMapper.readTree(string(row, "package_json")).path("rounds")
                    .path(((Number) row.get("round_no")).intValue() - 1).path("topic").asText();
            return topic.isBlank() ? "本轮学习主题" : topic;
        } catch (Exception ignored) { return "本轮学习主题"; }
    }

    @Transactional
    public void completeDirect(String userId, String sessionId, ExperimentDtos.TutorCompleteReq req) {
        access.requireSessionAccess(userId, sessionId, "direct");
        Map<String, Object> run = jdbc.queryForMap("SELECT r.*,a.id assignment_id_value FROM t_experiment_tutor_run r " +
                "JOIN t_student_experiment_assignment a ON a.id=r.assignment_id WHERE r.session_id=? AND a.user_id=? FOR UPDATE OF r", sessionId, userId);
        if (!"DIRECT_LLM".equals(run.get("group_code_snapshot"))) throw new BusinessException(ResultCode.FORBIDDEN);
        long initialSucceeded = Optional.ofNullable(jdbc.queryForObject("SELECT COUNT(*) FROM t_task WHERE session_id=? AND task_type='DIRECT_INIT' AND status='success'", Long.class, sessionId)).orElse(0L);
        if (initialSucceeded == 0) throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "完整讲解尚未生成");
        finishRun(run, userId, null);
    }

    @Transactional
    public void completeStructured(String userId, String sessionId, ExperimentDtos.TutorCompleteReq req) {
        Map<String, Object> run = jdbc.queryForMap("SELECT r.*,s.session_type,s.state_snapshot FROM t_experiment_tutor_run r " +
                "JOIN t_student_experiment_assignment a ON a.id=r.assignment_id JOIN t_session s ON s.id=r.session_id " +
                "WHERE r.session_id=? AND a.user_id=? FOR UPDATE OF r", sessionId, userId);
        String group = string(run, "group_code_snapshot");
        if (!Set.of("BASIC_TUTOR", "FULL_TUTOR").contains(group)) throw new BusinessException(ResultCode.FORBIDDEN);
        if ("COMPLETED".equals(string(run, "status"))) return;
        String sessionType = string(run, "session_type");
        if (!Set.of("explanation", "discussion").contains(sessionType)) throw new BusinessException(ResultCode.FORBIDDEN);
        access.requireSessionAccess(userId, sessionId, sessionType);
        if (!structuredSnapshotComplete(sessionType, string(run, "state_snapshot"))) {
            throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "当前教学尚未完成");
        }
        String learnerStateStatus = learnerStateStatusFromSnapshot(string(run, "state_snapshot"));
        finishRun(run, userId, learnerStateStatus == null ? latestLearnerStateStatus(sessionId) : learnerStateStatus);
    }

    @Transactional
    public void onInferenceSucceeded(InferenceTaskMsg msg, Map<String, Object> response) {
        List<Map<String, Object>> runs = jdbc.queryForList("SELECT r.*,a.user_id FROM t_experiment_tutor_run r " +
                "JOIN t_student_experiment_assignment a ON a.id=r.assignment_id WHERE r.session_id=?", msg.getSessionId());
        if (runs.isEmpty()) return;
        Map<String, Object> run = runs.get(0);
        String userId = string(run, "user_id");
        String responseJson;
        try { responseJson = objectMapper.writeValueAsString(response); } catch (Exception e) { responseJson = String.valueOf(response); }
        Boolean correctness = responseCorrectness(msg.getTaskType(), response);
        if (Set.of("EXPLANATION_COMMIT_ANSWER", "DISCUSSION_FEEDBACK").contains(msg.getTaskType()) && !isNextStepRetry(msg)) {
            jdbc.update("UPDATE t_experiment_tutor_run SET interaction_count=interaction_count+1,version=version+1 " +
                            "WHERE id=? AND group_code_snapshot IN ('BASIC_TUTOR','FULL_TUTOR') AND interaction_count<30",
                    run.get("id"));
        }
        recordStudentMessageFromTask(msg, correctness);
        recordStructuredObjectiveAnswer(run, msg, response, correctness);
        Map<String, Object> responseData = responseData(response);
        recordMessage(msg.getSessionId(), "tutor", msg.getTaskType(), responseJson, msg.getTaskId(), correctness,
                String.valueOf(responseData.getOrDefault("current_step", responseData.getOrDefault("status", ""))),
                responseData.get("knowledge_points"));
        events.recordSystem(userId, "TUTOR_MESSAGE_RECEIVED", Map.of("sessionId", msg.getSessionId(), "taskType", msg.getTaskType()));
        recordStepEvent(userId, msg, response, correctness);
        if (msg.getTaskType().endsWith("_INIT") && "FULL_TUTOR".equals(run.get("group_code_snapshot"))) {
            capturePersonalizationContext(run, userId);
        }
        if ("DIRECT_FOLLOWUP".equals(msg.getTaskType()) && ((Number) run.getOrDefault("interaction_count", 0)).intValue() >= 3) {
            finishRun(run, userId, null);
        } else if (structuredComplete(msg.getTaskType(), response)) finishRun(run, userId, learnerStateStatus(response));
    }

    private void finishRun(Map<String, Object> run, String userId, String learnerStateStatus) {
        if ("COMPLETED".equals(run.get("status"))) return;
        int round = ((Number) run.get("round_no")).intValue();
        jdbc.update("UPDATE t_experiment_tutor_run SET status='COMPLETED',completed_at=NOW(),learner_state_status=" +
                        "CASE WHEN group_code_snapshot='FULL_TUTOR' THEN ? ELSE learner_state_status END,version=version+1 WHERE id=? AND status='IN_PROGRESS'",
                "FULL_TUTOR".equals(run.get("group_code_snapshot")) && "READY".equals(learnerStateStatus) ? "READY" :
                        ("FULL_TUTOR".equals(run.get("group_code_snapshot")) ? "FAILED" : null), run.get("id"));
        jdbc.update("UPDATE t_session SET status='completed',completed_at=NOW() WHERE id=?", run.get("session_id"));
        jdbc.update("UPDATE t_student_stage_progress SET status='COMPLETED',completed_at=NOW(),last_heartbeat_at=NOW(),version=version+1 " +
                "WHERE assignment_id=? AND stage_id=?", run.get("assignment_id"), run.get("stage_id"));
        jdbc.update("UPDATE t_student_experiment_assignment SET student_state=?,last_activity_at=NOW(),updated_at=NOW(),version=version+1 WHERE id=?",
                "WAITING_ROUND_" + round + "_QUIZ", run.get("assignment_id"));
        events.recordSystem(userId, "TUTOR_COMPLETED", Map.of("runId", run.get("id"), "round", round, "mode", run.get("mode")));
        if ("FULL_TUTOR".equals(run.get("group_code_snapshot")) && !"READY".equals(learnerStateStatus)) {
            events.recordSystem(userId, "LEARNER_STATE_FAILED", Map.of("runId", run.get("id"), "round", round));
        }
    }

    @SuppressWarnings("unchecked")
    private void capturePersonalizationContext(Map<String, Object> run, String userId) {
        try {
            String snapshotJson = jdbc.queryForObject("SELECT state_snapshot FROM t_session WHERE id=?", String.class, run.get("session_id"));
            if (snapshotJson == null || snapshotJson.isBlank()) return;
            Map<String, Object> snapshot = objectMapper.readValue(snapshotJson, Map.class);
            Map<String, Object> context = new LinkedHashMap<>();
            for (String key : List.of("system_prompt", "user_profile_text", "weak_points", "related_kps", "knowledge_points")) {
                if (snapshot.containsKey(key)) context.put(key, snapshot.get(key));
            }
            context.put("cross_session_enabled", snapshot.getOrDefault("cross_session_enabled", false));
            jdbc.update("UPDATE t_experiment_tutor_run SET personalization_context=?::jsonb,version=version+1 WHERE id=?",
                    objectMapper.writeValueAsString(context), run.get("id"));
            if (Boolean.TRUE.equals(run.get("personalization_enabled"))) {
                events.recordSystem(userId, "PERSONALIZATION_CONTEXT_APPLIED",
                        Map.of("runId", run.get("id"), "status", "APPLIED", "contextFields", context.keySet()));
            }
        } catch (Exception ignored) {
            if (Boolean.TRUE.equals(run.get("personalization_enabled"))) {
                events.recordSystem(userId, "PERSONALIZATION_CONTEXT_FAILED", Map.of("runId", run.get("id")));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void recordStepEvent(String userId, InferenceTaskMsg msg, Map<String, Object> response, Boolean correctness) {
        Object raw = response.get("data");
        Map<String, Object> data = raw instanceof Map<?, ?> ? (Map<String, Object>) raw : Map.of();
        if ("EXPLANATION_GETSTEP".equals(msg.getTaskType())) {
            events.recordUserAction(userId, "TUTOR_STEP_STARTED", Map.of("taskType", msg.getTaskType()), null, null, msg.getSessionId());
        } else if (isNextStepRetry(msg)) {
            events.recordUserAction(userId, "TUTOR_NEXT_STEP_RETRIED", Map.of("taskType", msg.getTaskType()),
                    null, null, msg.getSessionId());
        } else if ("EXPLANATION_COMMIT_ANSWER".equals(msg.getTaskType()) || "DISCUSSION_FEEDBACK".equals(msg.getTaskType())) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("taskType", msg.getTaskType());
            detail.put("correctness", correctness);
            detail.put("status", data.get("status"));
            detail.put("currentStep", data.get("current_step"));
            events.recordUserAction(userId, "TUTOR_STEP_COMPLETED", detail, null, null, msg.getSessionId());
        }
    }

    @SuppressWarnings("unchecked")
    private String learnerStateStatus(Map<String, Object> response) {
        Object raw = response.get("data");
        if (raw instanceof Map<?, ?> data && data.get("learner_state_status") != null)
            return String.valueOf(data.get("learner_state_status"));
        return null;
    }

    private String latestLearnerStateStatus(String sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT result FROM t_task WHERE session_id=? AND task_type IN " +
                "('EXPLANATION_COMMIT_ANSWER','DISCUSSION_FEEDBACK') AND status='success' ORDER BY created_at DESC LIMIT 1", sessionId);
        if (rows.isEmpty() || string(rows.get(0), "result") == null) return null;
        try {
            JsonNode data = objectMapper.readTree(string(rows.get(0), "result")).path("data");
            return data.hasNonNull("learner_state_status") ? data.get("learner_state_status").asText() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String learnerStateStatusFromSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) return null;
        try {
            JsonNode snapshot = objectMapper.readTree(snapshotJson);
            return snapshot.hasNonNull("learner_state_status") ? snapshot.get("learner_state_status").asText() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean structuredSnapshotComplete(String sessionType, String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) return false;
        try {
            JsonNode snapshot = objectMapper.readTree(snapshotJson);
            if ("explanation".equals(sessionType)) return snapshot.path("in_summary").asBoolean(false)
                    && snapshot.path("current_step").asInt(-1) == 0;
            return snapshot.path("is_complete").asBoolean(false)
                    || "completed".equalsIgnoreCase(snapshot.path("status").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Boolean responseCorrectness(String taskType, Map<String, Object> response) {
        Object raw = response.get("data");
        if (!(raw instanceof Map<?, ?> data)) return null;
        if ("EXPLANATION_COMMIT_ANSWER".equals(taskType))
            return "True".equals(String.valueOf(data.get("error_analysis")));
        if ("DISCUSSION_FEEDBACK".equals(taskType)) {
            String value = String.valueOf(data.get("correctness")).toLowerCase(Locale.ROOT);
            if (Set.of("true", "correct", "正确").contains(value)) return true;
            if (Set.of("false", "incorrect", "错误").contains(value)) return false;
        }
        return null;
    }

    private String publishInit(ExperimentGroup group, String mode, String userId, String runId, String sessionId,
                               String question, int round) {
        String modelQuestion = modelQuestionForRun(runId, group, question, round);
        if (group == ExperimentGroup.DIRECT_LLM) {
            return tasks.publishDirectInit(sessionId, userId, Map.of("question", modelQuestion, "user_id", userId, "experiment_run_id", runId));
        }
        TutorFeatureFlags flags = tutorFeatureFlags(group, round);
        String learnerStateScope = learnerStateScopeForRun(runId);
        ExperimentTeachingContext context = teachingContextForRun(runId, group, round);
        String displayQuestion = context != null && !context.topic().isBlank() ? "本轮学习主题：" + context.topic() : question;
        if ("explanation".equals(mode)) {
            ExplanationInitReq request = new ExplanationInitReq();
            request.setProcessedQuestion(modelQuestion); request.setDisplayQuestion(displayQuestion); request.setUserId(userId); request.setPersonalizedMode(flags.personalizedMode());
            request.setAdaptiveMode(flags.adaptiveMode()); request.setCrossSessionEnabled(flags.crossSessionEnabled()); request.setPersistLearnerState(flags.persistLearnerState()); request.setExperimentRunId(runId); request.setLearnerStateScope(learnerStateScope);
            applyTeachingContext(request, context);
            return tasks.publishExplanationInit(sessionId, userId, request);
        }
        DiscussionInitReq request = new DiscussionInitReq();
        request.setProcessedQuestion(modelQuestion); request.setDisplayQuestion(displayQuestion); request.setUserId(userId); request.setPersonalizedMode(flags.personalizedMode());
        request.setAdaptiveMode(flags.adaptiveMode()); request.setCrossSessionEnabled(flags.crossSessionEnabled()); request.setPersistLearnerState(flags.persistLearnerState()); request.setExperimentRunId(runId); request.setLearnerStateScope(learnerStateScope);
        applyTeachingContext(request, context);
        return tasks.publishDiscussionInit(sessionId, userId, request);
    }

    static TutorFeatureFlags tutorFeatureFlags(ExperimentGroup group, int round) {
        boolean fullTutor = group == ExperimentGroup.FULL_TUTOR;
        boolean structuredTutor = group.structured();
        return new TutorFeatureFlags(fullTutor, structuredTutor, fullTutor && round > 1, fullTutor);
    }

    static String learnerStateScope(String experimentId, String assignmentId) {
        return "experiment:" + experimentId + ":assignment:" + assignmentId;
    }

    static ExperimentTeachingContext teachingContext(ExperimentGroup group, JsonNode round,
                                                      List<? extends Map<String, ?>> priorRounds) {
        List<String> objectives = new ArrayList<>();
        for (JsonNode objective : round.path("objectives")) objectives.add(objective.asText());
        List<Map<String, Object>> prior = new ArrayList<>();
        if (group == ExperimentGroup.FULL_TUTOR) {
            for (Map<String, ?> source : priorRounds) {
                Map<String, Object> item = new LinkedHashMap<>();
                for (String key : List.of("round", "mode", "learnerState", "interactionCount", "incorrectCount")) {
                    if (source.containsKey(key)) item.put(key, source.get(key));
                }
                prior.add(item);
            }
        }
        return new ExperimentTeachingContext(round.path("topic").asText(), objectives, prior, 30, true);
    }

    private ExperimentTeachingContext teachingContextForRun(String runId, ExperimentGroup group, int round) {
        try {
            Map<String, Object> row = jdbc.queryForMap("SELECT cv.package_json::text package_json,r.assignment_id FROM t_experiment_tutor_run r " +
                    "JOIN t_student_experiment_assignment a ON a.id=r.assignment_id JOIN t_experiment_content_version cv ON cv.experiment_id=a.experiment_id AND cv.version=a.content_version WHERE r.id=?", runId);
            return teachingContext(group, objectMapper.readTree(string(row, "package_json")).path("rounds").path(round - 1),
                    group == ExperimentGroup.FULL_TUTOR ? priorRoundStates(string(row, "assignment_id"), round) : List.of());
        } catch (Exception ignored) { return null; }
    }

    private void applyTeachingContext(ExplanationInitReq request, ExperimentTeachingContext context) {
        if (context == null) return;
        request.setTopic(context.topic()); request.setObjectives(context.objectives());
        request.setPriorRoundState(context.priorRoundState()); request.setInteractionLimit(context.interactionLimit()); request.setForceCoverageOnFinalTurn(context.forceCoverageOnFinalTurn());
    }

    private void applyTeachingContext(DiscussionInitReq request, ExperimentTeachingContext context) {
        if (context == null) return;
        request.setTopic(context.topic()); request.setObjectives(context.objectives());
        request.setPriorRoundState(context.priorRoundState()); request.setInteractionLimit(context.interactionLimit()); request.setForceCoverageOnFinalTurn(context.forceCoverageOnFinalTurn());
    }

    private String modelQuestionForRun(String runId, ExperimentGroup group, String legacyQuestion, int round) {
        try {
            Map<String, Object> row = jdbc.queryForMap("SELECT cv.package_json::text package_json,r.assignment_id FROM t_experiment_tutor_run r " +
                            "JOIN t_student_experiment_assignment a ON a.id=r.assignment_id " +
                            "JOIN t_experiment_content_version cv ON cv.experiment_id=a.experiment_id AND cv.version=a.content_version WHERE r.id=?", runId);
            JsonNode content = objectMapper.readTree(string(row, "package_json"));
            JsonNode roundDefinition = content.path("rounds").path(round - 1).deepCopy();
            if (group == ExperimentGroup.DIRECT_LLM && roundDefinition.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) roundDefinition).put("directPrompt",
                        content.path("directPrompts").path("M" + round).asText());
            }
            List<Map<String, Object>> prior = group == ExperimentGroup.FULL_TUTOR
                    ? priorRoundStates(string(row, "assignment_id"), round) : List.of();
            return modelQuestion(group, roundDefinition, legacyQuestion, prior);
        } catch (Exception ignored) {
            return legacyQuestion;
        }
    }

    static String modelQuestion(ExperimentGroup group, JsonNode round, String legacyQuestion,
                                List<? extends Map<String, ?>> priorRounds) {
        if (group == ExperimentGroup.DIRECT_LLM) {
            String prompt = round.path("directPrompt").asText();
            return prompt.isBlank() ? legacyQuestion : prompt;
        }
        StringBuilder context = new StringBuilder("本轮教学主题：").append(round.path("topic").asText()).append("\n学习目标：");
        for (JsonNode objective : round.path("objectives")) context.append("\n- ").append(objective.asText());
        if (group == ExperimentGroup.FULL_TUTOR && !priorRounds.isEmpty()) {
            context.append("\n跨轮学习状态（仅结构化过程指标）：");
            for (Map<String, ?> state : priorRounds) context.append("\n- ").append(state);
        }
        context.append("\n请据此调整讲解细致程度；不得提供正式测试题、选项、答案或评分。");
        return context.toString();
    }

    private List<Map<String, Object>> priorRoundStates(String assignmentId, int currentRound) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT r.round_no,r.mode,r.learner_state_status,r.interaction_count," +
                        "COUNT(tm.id) FILTER (WHERE tm.correctness=FALSE) incorrect_count FROM t_experiment_tutor_run r " +
                        "LEFT JOIN t_tutor_message tm ON tm.tutor_run_id=r.id WHERE r.assignment_id=? AND r.round_no<? AND r.status='COMPLETED' " +
                        "GROUP BY r.round_no,r.mode,r.learner_state_status,r.interaction_count ORDER BY r.round_no", assignmentId, currentRound);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) result.add(Map.of("round", row.get("round_no"), "mode", row.get("mode"),
                "learnerState", row.get("learner_state_status"), "interactionCount", row.get("interaction_count"),
                "incorrectCount", row.get("incorrect_count")));
        return result;
    }

    private String learnerStateScopeForRun(String runId) {
        Map<String, Object> scope = jdbc.queryForMap("SELECT a.experiment_id, a.id assignment_id FROM t_experiment_tutor_run r " +
                        "JOIN t_student_experiment_assignment a ON a.id=r.assignment_id WHERE r.id=?", runId);
        return learnerStateScope(string(scope, "experiment_id"), string(scope, "assignment_id"));
    }

    record TutorFeatureFlags(boolean personalizedMode, boolean adaptiveMode,
                             boolean crossSessionEnabled, boolean persistLearnerState) {}
    record ExperimentTeachingContext(String topic, List<String> objectives, List<Map<String, Object>> priorRoundState, int interactionLimit,
                                     boolean forceCoverageOnFinalTurn) {}

    private Map<String, Object> tutorConfig(Map<String, Object> assignment, GlobalStage stage, ExperimentGroup group) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT s.id stage_id,g.tutor_question_id question_id,q.prompt FROM t_experiment_stage s " +
                "JOIN t_experiment_stage_group_config g ON g.stage_id=s.id AND g.group_code=? AND g.participating=TRUE " +
                "JOIN t_experiment_question q ON q.id=g.tutor_question_id WHERE s.experiment_id=? AND s.stage_code=?",
                group.name(), assignment.get("experiment_id"), stage.name());
        if (rows.isEmpty()) throw new BusinessException(ResultCode.NOT_FOUND, "当前组别未配置 Tutor 题目");
        return rows.get(0);
    }

    static GlobalStage tutorStage(StudentState state) {
        String name = state.name();
        if (!name.matches("(?:WAITING_)?ROUND_[1-5]_TUTOR(?:_IN_PROGRESS)?")) {
            throw new BusinessException(ResultCode.EXPERIMENT_STAGE_LOCKED);
        }
        return GlobalStage.valueOf(name.replace("WAITING_", "").replace("_IN_PROGRESS", ""));
    }

    private static String selectedGroup(Map<String, Object> assignment) {
        String selected = string(assignment, "selected_mode");
        return selected == null ? string(assignment, "group_code") : selected;
    }
    private Map<String, Object> lockedAssignment(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.*,e.current_stage_code FROM t_student_experiment_assignment a " +
                "JOIN t_experiment e ON e.id=a.experiment_id WHERE a.user_id=? FOR UPDATE OF a", userId);
        if (rows.isEmpty()) throw new BusinessException(ResultCode.EXPERIMENT_NOT_ASSIGNED); return rows.get(0);
    }
    private ExperimentDtos.TutorStartVO vo(Map<String, Object> run, String taskId, boolean resumed) {
        String mode=string(run,"mode"), session=string(run,"session_id");
        String route="discussion".equals(mode)?"/ai-tutor/discussion/"+session:"explanation".equals(mode)?"/ai-tutor/explanation/"+session:"/experiment/tutor/direct/"+session;
        return new ExperimentDtos.TutorStartVO(string(run,"id"),string(run,"group_code_snapshot"),((Number)run.get("round_no")).intValue(),mode,session,taskId,resumed,route);
    }
    private void recordMessage(String sessionId, String role, String type, String content, String key, Boolean correctness) {
        recordMessage(sessionId, role, type, content, key, correctness, null, null);
    }
    private void recordMessage(String sessionId, String role, String type, String content, String key, Boolean correctness,
                               String step, Object knowledgePoints) {
        String knowledgeJson;
        try { knowledgeJson = knowledgePoints == null ? null : objectMapper.writeValueAsString(knowledgePoints); }
        catch (Exception ignored) { knowledgeJson = null; }
        jdbc.update("INSERT INTO t_tutor_message(id,tutor_run_id,session_id,message_key,role,message_type,content,correctness) " +
                        "SELECT ?,r.id,?,?,?,?,?,? FROM t_experiment_tutor_run r WHERE r.session_id=? ON CONFLICT(session_id,message_key) DO NOTHING",
                UUID.randomUUID().toString(), sessionId, key, role, type, content, correctness, sessionId);
        if (step != null || knowledgeJson != null) {
            jdbc.update("UPDATE t_tutor_message SET step=?,knowledge_points=?::jsonb WHERE session_id=? AND message_key=?",
                    step, knowledgeJson, sessionId, key);
        }
    }

    @SuppressWarnings("unchecked")
    private void recordStudentMessageFromTask(InferenceTaskMsg msg, Boolean correctness) {
        if (msg.getRequestBodyJson() == null || msg.getRequestBodyJson().isBlank()) return;
        try {
            Map<String, Object> request = objectMapper.readValue(msg.getRequestBodyJson(), Map.class);
            Object content = "EXPLANATION_COMMIT_ANSWER".equals(msg.getTaskType()) ? request.get("option") :
                    ("DISCUSSION_FEEDBACK".equals(msg.getTaskType()) ? request.get("feedback") : null);
            if (content != null) {
                if (NEXT_STEP_RETRY.equals(String.valueOf(content))) {
                    recordMessage(msg.getSessionId(), "student", "NEXT_STEP_RETRY", String.valueOf(content),
                            msg.getTaskId() + ":input", null);
                    return;
                }
                recordMessage(msg.getSessionId(), "student", msg.getTaskType(), String.valueOf(content),
                        msg.getTaskId() + ":input", correctness);
                events.recordUserAction(msg.getUserId(), "TUTOR_MESSAGE_SENT", Map.of("taskType", msg.getTaskType()),
                        null, null, msg.getSessionId());
            }
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("unchecked")
    private void recordStructuredObjectiveAnswer(Map<String, Object> run, InferenceTaskMsg msg,
                                                  Map<String, Object> response, Boolean correctness) {
        if (!Set.of("BASIC_TUTOR", "FULL_TUTOR").contains(string(run, "group_code_snapshot")) || correctness == null ||
                !Set.of("EXPLANATION_COMMIT_ANSWER", "DISCUSSION_FEEDBACK").contains(msg.getTaskType()) ||
                msg.getRequestBodyJson() == null || msg.getRequestBodyJson().isBlank() || isNextStepRetry(msg)) return;
        try {
            Map<String, Object> request = objectMapper.readValue(msg.getRequestBodyJson(), Map.class);
            boolean explanation = "EXPLANATION_COMMIT_ANSWER".equals(msg.getTaskType());
            if (!explanation && !"option".equals(request.get("submission_type"))) return;
            Object answer = explanation ? request.get("option") : request.get("feedback");
            if (answer == null) return;
            Map<String, Object> data = responseData(response);
            Object submittedStep = request.get("active_step_id");
            if (submittedStep == null || String.valueOf(submittedStep).isBlank()) {
                submittedStep = explanation ? data.get("current_step") : data.get("active_node_id");
            }
            if (submittedStep == null || String.valueOf(submittedStep).isBlank()) return;
            String stepKey = (explanation ? "explanation:" : "discussion:") + submittedStep;
            String answerJson = objectMapper.writeValueAsString(Map.of("value", answer));
            jdbc.update("INSERT INTO t_experiment_tutor_step_record(id,assignment_id,tutor_run_id,task_id,round_no,learning_mode," +
                            "step_key,submission_type,submitted_answer,correctness,retry_count) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?," +
                            "COALESCE((SELECT MAX(retry_count)+1 FROM t_experiment_tutor_step_record WHERE tutor_run_id=? AND step_key=?),0)) " +
                            "ON CONFLICT(tutor_run_id,task_id) DO NOTHING",
                    UUID.randomUUID().toString(), run.get("assignment_id"), run.get("id"), msg.getTaskId(), run.get("round_no"),
                    run.get("mode"), stepKey, "option", answerJson, correctness, run.get("id"), stepKey);
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("unchecked")
    private boolean isNextStepRetry(InferenceTaskMsg msg) {
        if (!"DISCUSSION_FEEDBACK".equals(msg.getTaskType()) || msg.getRequestBodyJson() == null || msg.getRequestBodyJson().isBlank()) return false;
        try {
            Map<String, Object> request = objectMapper.readValue(msg.getRequestBodyJson(), Map.class);
            return NEXT_STEP_RETRY.equals(String.valueOf(request.get("feedback")));
        } catch (Exception ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseData(Map<String, Object> response) {
        Object raw = response.get("data");
        return raw instanceof Map<?, ?> ? (Map<String, Object>) raw : Map.of();
    }
    @SuppressWarnings("unchecked")
    private boolean structuredComplete(String type, Map<String, Object> response) {
        Object dataObj=response.get("data"); if (!(dataObj instanceof Map<?,?> data)) return false;
        if (type.startsWith("EXPLANATION")) return Boolean.TRUE.equals(data.get("in_summary")) && Objects.equals(String.valueOf(data.get("current_step")),"0");
        if (type.startsWith("DISCUSSION")) return Boolean.TRUE.equals(data.get("is_complete")) || "completed".equalsIgnoreCase(String.valueOf(data.get("status")));
        return false;
    }
    private static String string(Map<String,Object> row,String key){Object v=row.get(key);return v==null?null:String.valueOf(v);}
}
