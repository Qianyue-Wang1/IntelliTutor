package com.intellitutor.scut.modules.experiment.service;

import cn.hutool.crypto.digest.BCrypt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.config.FeatureProperties;
import com.intellitutor.scut.modules.experiment.domain.ExperimentMode;
import com.intellitutor.scut.modules.experiment.domain.GlobalStage;
import com.intellitutor.scut.modules.experiment.domain.StudentState;
import com.intellitutor.scut.modules.experiment.dto.ExperimentContextVO;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExperimentOrchestrationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final FeatureProperties features;
    private final ExperimentEventService events;
    private final ExperimentAccessService access;

    public ExperimentContextVO context(String userId) {
        if (!features.isExperimentModeEnabled()) {
            return new ExperimentContextVO(false, false, null, null, null, null, null,
                    null, 0, null, null, null, null, 0, "/", List.of("FREE_TUTOR"), null, null, now());
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.*,e.name experiment_name,e.current_stage_code," +
                "s.id stage_id,s.stage_type,s.lecture_markdown FROM t_student_experiment_assignment a " +
                "JOIN t_experiment e ON e.id=a.experiment_id LEFT JOIN t_experiment_stage s " +
                "ON s.experiment_id=e.id AND s.stage_code=e.current_stage_code WHERE a.user_id=? " +
                "ORDER BY a.created_at DESC LIMIT 1", userId);
        if (rows.isEmpty()) {
            return new ExperimentContextVO(true, false, null, null, null, null, null,
                    null, 0, null, null, null, null, 0, "/login", List.of(), "账号尚未分配实验", null, now());
        }
        Map<String, Object> row = rows.get(0);
        String assignmentId = string(row, "id");
        String global = string(row, "current_stage_code");
        String stateName = string(row, "student_state");
        StudentState state = StudentState.valueOf(stateName);
        String group = string(row, "selected_mode");
        if (group == null) group = string(row, "group_code");
        int round = number(row, "current_round");
        Resource resource = new Resource(null, null, string(row, "current_learning_method"), null, false);

        String participation = string(row, "participation_status");
        String route;
        List<String> actions = new ArrayList<>();
        String waiting = null;
        if (!"ACTIVE".equals(participation)) {
            route = "/experiment/finished";
            waiting = "你已不再参加本实验。如有疑问，请联系实验管理员。";
        } else if (state == StudentState.MUST_CHANGE_PASSWORD) {
            route = "/experiment/password";
            actions.add("CHANGE_PASSWORD");
        } else if (state == StudentState.EXPERIMENT_FINISHED) {
            route = "/experiment/records";
        } else if (state == StudentState.WAITING_PRETEST || state == StudentState.PRETEST_IN_PROGRESS) {
            route = "/experiment/assessment/pretest";
            actions.add("START_OR_CONTINUE_ASSESSMENT");
        } else if (state == StudentState.WAITING_MODE_SELECTION) {
            route = "/experiment/mode";
            actions.add("SELECT_MODE");
        } else if (state.name().matches("WAITING_ROUND_[1-5]_TUTOR")) {
            if ("BASIC_TUTOR".equals(group) || "FULL_TUTOR".equals(group)) {
                route = "/experiment/tutor/mode";
                actions.add("SELECT_LEARNING_METHOD");
            } else if ("NO_AI".equals(group)) {
                route = "/experiment/no-ai";
                actions.add("START_NO_AI_LEARNING");
            } else {
                route = "/experiment/tutor/start";
                actions.add("START_TUTOR");
            }
        } else if (state.name().matches("ROUND_[1-5]_TUTOR_IN_PROGRESS")) {
            if ("NO_AI".equals(group)) {
                route = "/experiment/no-ai";
                actions.add("CONTINUE_NO_AI_LEARNING");
            } else {
                Resource tutorResource = currentTutorResource(assignmentId, round);
                resource = tutorResource;
                if (tutorResource.sessionId == null || !tutorResource.initialized) {
                    route = "/experiment/tutor/start";
                    actions.add("START_TUTOR");
                } else if ("discussion".equalsIgnoreCase(tutorResource.mode)) {
                    route = "/ai-tutor/discussion/" + tutorResource.sessionId;
                    actions.add("CONTINUE_TUTOR");
                } else if ("explanation".equalsIgnoreCase(tutorResource.mode)) {
                    route = "/ai-tutor/explanation/" + tutorResource.sessionId;
                    actions.add("CONTINUE_TUTOR");
                } else {
                    route = "/experiment/tutor/direct/" + tutorResource.sessionId;
                    actions.add("CONTINUE_TUTOR");
                }
            }
        } else if (state.name().matches("WAITING_ROUND_[1-5]_QUIZ|ROUND_[1-5]_QUIZ_IN_PROGRESS")) {
            route = "/experiment/assessment/q" + round;
            actions.add("START_OR_CONTINUE_ASSESSMENT");
        } else if (state == StudentState.WAITING_POSTTEST || state == StudentState.POSTTEST_IN_PROGRESS) {
            route = "/experiment/assessment/posttest";
            actions.add("START_OR_CONTINUE_ASSESSMENT");
        } else if (state == StudentState.WAITING_CREDENTIAL) {
            route = "/experiment/credential";
            actions.add("ISSUE_OR_VIEW_CREDENTIAL");
        } else {
            route = "/experiment/waiting";
            waiting = "当前实验状态暂不可继续。";
        }

        JsonNode stageContent = null;
        if (row.get("lecture_markdown") != null && StudentState.WAITING_LECTURE.name().equals(stateName)) {
            stageContent = objectMapper.valueToTree(Map.of("markdown", row.get("lecture_markdown")));
        }
        return new ExperimentContextVO(true, true, string(row, "experiment_id"), string(row, "experiment_name"),
                global, stateName, participation, group, round, resource.mode, resource.sessionId,
                resource.attemptId, resource.deadline, number(row, "state_version"), route, actions, waiting, stageContent, now());
    }

    @Transactional
    public ExperimentDtos.CommandResult selectMode(String userId, ExperimentDtos.SelectModeReq req) {
        Map<String, Object> row = assignmentForUpdate(userId);
        ExperimentDtos.CommandResult existing = existingCommand(row, "SELECT_MODE", req.idempotencyKey());
        if (existing != null) return existing;
        if (!"ACTIVE".equals(string(row, "participation_status"))
                || !"WAITING_MODE_SELECTION".equals(string(row, "student_state"))
                || string(row, "selected_mode") != null) {
            throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "当前不能选择实验模式");
        }
        if (number(row, "state_version") != req.stateVersion()) {
            throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "实验状态已更新，请刷新页面");
        }
        ExperimentMode mode;
        try {
            mode = ExperimentMode.fromCode(req.mode());
        } catch (IllegalArgumentException error) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "未知实验模式");
        }
        int updated = jdbc.update("UPDATE t_student_experiment_assignment SET selected_mode=?,current_round=1," +
                        "student_state='WAITING_ROUND_1_TUTOR',content_version=COALESCE(content_version," +
                        "(SELECT content_version FROM t_experiment WHERE id=?)),state_version=state_version+1," +
                        "updated_at=NOW() WHERE id=? AND state_version=? AND student_state='WAITING_MODE_SELECTION' AND selected_mode IS NULL",
                mode.name(), row.get("experiment_id"), row.get("id"), req.stateVersion());
        if (updated != 1) throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "实验状态已更新，请刷新页面");
        ExperimentDtos.CommandResult result = new ExperimentDtos.CommandResult(mode.name(), "WAITING_ROUND_1_TUTOR", 1,
                req.stateVersion() + 1, mode == ExperimentMode.NO_AI ? "/experiment/no-ai" : "/experiment/tutor/start");
        recordCommand(row, "SELECT_MODE", req.idempotencyKey(), result);
        events.recordSystem(userId, "EXPERIMENT_MODE_SELECTED", Map.of("mode", mode.name()));
        return result;
    }

    @Transactional
    public ExperimentDtos.CommandResult selectLearningMethod(String userId, ExperimentDtos.SelectLearningMethodReq req) {
        Map<String, Object> row = assignmentForUpdate(userId);
        ExperimentDtos.CommandResult existing = existingCommand(row, "SELECT_LEARNING_METHOD", req.idempotencyKey());
        if (existing != null) return existing;
        String mode = string(row, "selected_mode");
        if (!("BASIC_TUTOR".equals(mode) || "FULL_TUTOR".equals(mode))
                || number(row, "current_round") != req.round()
                || !waitingTutorState(req.round()).equals(string(row, "student_state"))) {
            throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "当前不能选择该学习方式");
        }
        if (number(row, "state_version") != req.stateVersion()) {
            throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "实验状态已更新，请刷新页面");
        }
        String method = req.method().toLowerCase(java.util.Locale.ROOT);
        if (!("explanation".equals(method) || "discussion".equals(method))) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "未知学习方式");
        }
        String nextState = tutorInProgressState(req.round());
        int updated = jdbc.update("UPDATE t_student_experiment_assignment SET current_learning_method=?,student_state=?," +
                        "state_version=state_version+1,updated_at=NOW() WHERE id=? AND state_version=? AND student_state=?",
                method, nextState, row.get("id"), req.stateVersion(), waitingTutorState(req.round()));
        if (updated != 1) throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "实验状态已更新，请刷新页面");
        ExperimentDtos.CommandResult result = new ExperimentDtos.CommandResult(mode, nextState, req.round(),
                req.stateVersion() + 1, "/experiment/tutor/start");
        recordCommand(row, "SELECT_LEARNING_METHOD", req.idempotencyKey(), result);
        events.recordSystem(userId, "LEARNING_METHOD_SELECTED", Map.of("round", req.round(), "method", method));
        return result;
    }

    @Transactional
    public void changeMandatoryPassword(String userId, ExperimentDtos.MandatoryPasswordReq req) {
        Map<String, Object> row = assignmentForUpdate(userId);
        if (!"MUST_CHANGE_PASSWORD".equals(row.get("student_state"))) {
            throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "无需再次修改初始密码");
        }
        String hash = jdbc.queryForObject("SELECT password FROM t_user WHERE id=?", String.class, userId);
        if (hash == null || !BCrypt.checkpw(req.currentPassword(), hash)) {
            throw new BusinessException(ResultCode.PASSWORD_ERROR);
        }
        if (BCrypt.checkpw(req.newPassword(), hash)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "新密码不能与初始密码相同");
        }
        OffsetDateTime now = now();
        jdbc.update("UPDATE t_user SET password=?,must_change_password=FALSE,password_changed_at=?,updated_at=NOW() WHERE id=?",
                BCrypt.hashpw(req.newPassword(), BCrypt.gensalt()), now, userId);
        int updated = jdbc.update("UPDATE t_student_experiment_assignment SET " +
                "student_state=COALESCE(password_resume_state,'WAITING_LECTURE'),password_resume_state=NULL," +
                "started_at=COALESCE(started_at,NOW()),updated_at=NOW(),version=version+1 WHERE id=? AND student_state='MUST_CHANGE_PASSWORD'",
                row.get("id"));
        if (updated != 1) throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT);
        events.recordSystem(userId, "PASSWORD_CHANGED", Map.of());
    }

    @Transactional
    public void startLecture(String userId) {
        access.requireStage(userId, GlobalStage.LECTURE);
        Map<String, Object> row = assignmentForUpdate(userId);
        String state = string(row, "student_state");
        String stageId = stageId(string(row, "experiment_id"), GlobalStage.LECTURE.name());
        jdbc.update("INSERT INTO t_student_stage_progress(id,assignment_id,stage_id,status,started_at,last_heartbeat_at) " +
                "VALUES(?,?,?,'IN_PROGRESS',NOW(),NOW()) ON CONFLICT(assignment_id,stage_id) DO UPDATE SET " +
                "status=CASE WHEN t_student_stage_progress.status='COMPLETED' THEN 'COMPLETED' ELSE 'IN_PROGRESS' END," +
                "started_at=COALESCE(t_student_stage_progress.started_at,NOW()),last_heartbeat_at=NOW()",
                UUID.randomUUID().toString(), row.get("id"), stageId);
        if ("WAITING_LECTURE".equals(state)) {
            jdbc.update("UPDATE t_student_experiment_assignment SET student_state='LECTURE_IN_PROGRESS',last_activity_at=NOW()," +
                    "updated_at=NOW(),version=version+1 WHERE id=? AND student_state='WAITING_LECTURE'", row.get("id"));
            events.recordSystem(userId, "LECTURE_STARTED", Map.of());
        } else {
            events.recordSystem(userId, "SESSION_RESUMED", Map.of("kind", "LECTURE"));
        }
    }

    public void heartbeatLecture(String userId, ExperimentDtos.HeartbeatReq req) {
        access.requireStage(userId, GlobalStage.LECTURE);
        Map<String, Object> row = assignment(userId);
        String stageId = stageId(string(row, "experiment_id"), GlobalStage.LECTURE.name());
        long delta = Math.max(0, Math.min(req.activeSeconds(), 60));
        jdbc.update("UPDATE t_student_stage_progress SET last_heartbeat_at=NOW(),active_seconds=active_seconds+?,version=version+1 " +
                "WHERE assignment_id=? AND stage_id=? AND status='IN_PROGRESS'", delta, row.get("id"), stageId);
        jdbc.update("UPDATE t_student_experiment_assignment SET last_activity_at=NOW() WHERE id=?", row.get("id"));
    }

    @Transactional
    public void completeLecture(String userId) {
        access.requireStage(userId, GlobalStage.LECTURE);
        Map<String, Object> row = assignmentForUpdate(userId);
        if (!"LECTURE_IN_PROGRESS".equals(row.get("student_state"))) {
            throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "请先开始讲义学习");
        }
        String stageId = stageId(string(row, "experiment_id"), GlobalStage.LECTURE.name());
        jdbc.update("UPDATE t_student_stage_progress SET status='COMPLETED',completed_at=NOW(),last_heartbeat_at=NOW(),version=version+1 " +
                "WHERE assignment_id=? AND stage_id=? AND status='IN_PROGRESS'", row.get("id"), stageId);
        jdbc.update("UPDATE t_student_experiment_assignment SET student_state='WAITING_PRETEST',last_activity_at=NOW()," +
                "updated_at=NOW(),version=version+1 WHERE id=? AND student_state='LECTURE_IN_PROGRESS'", row.get("id"));
        events.recordSystem(userId, "LECTURE_COMPLETED", Map.of());
    }

    public Map<String, Object> assignment(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.*,e.current_stage_code,e.status experiment_status " +
                "FROM t_student_experiment_assignment a JOIN t_experiment e ON e.id=a.experiment_id WHERE a.user_id=? " +
                "ORDER BY a.created_at DESC LIMIT 1", userId);
        if (rows.isEmpty()) throw new BusinessException(ResultCode.EXPERIMENT_NOT_ASSIGNED);
        return rows.get(0);
    }

    private Map<String, Object> assignmentForUpdate(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.*,e.current_stage_code FROM t_student_experiment_assignment a " +
                "JOIN t_experiment e ON e.id=a.experiment_id WHERE a.user_id=? ORDER BY a.created_at DESC LIMIT 1 FOR UPDATE OF a", userId);
        if (rows.isEmpty()) throw new BusinessException(ResultCode.EXPERIMENT_NOT_ASSIGNED);
        return rows.get(0);
    }

    private ExperimentDtos.CommandResult existingCommand(Map<String, Object> assignment, String commandType, String idempotencyKey) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT response_json::text response_json FROM t_experiment_command " +
                "WHERE assignment_id=? AND command_type=? AND idempotency_key=?", assignment.get("id"), commandType, idempotencyKey);
        if (rows.isEmpty()) return null;
        try {
            return objectMapper.treeToValue(objectMapper.readTree(string(rows.get(0), "response_json")), ExperimentDtos.CommandResult.class);
        } catch (Exception error) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "实验命令记录不可读取");
        }
    }

    private void recordCommand(Map<String, Object> assignment, String commandType, String idempotencyKey,
                               ExperimentDtos.CommandResult result) {
        try {
            jdbc.update("INSERT INTO t_experiment_command(id,assignment_id,command_type,idempotency_key,response_json) VALUES(?,?,?,?,?::jsonb)",
                    UUID.randomUUID().toString(), assignment.get("id"), commandType, idempotencyKey,
                    objectMapper.writeValueAsString(result));
        } catch (Exception error) {
            throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "实验命令重复或保存失败");
        }
    }

    private Resource currentTutorResource(String assignmentId, int round) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT r.mode,r.session_id,s.python_session_id FROM t_experiment_tutor_run r " +
                "LEFT JOIN t_session s ON s.id=r.session_id WHERE r.assignment_id=? AND r.round_no=?", assignmentId, round);
        if (rows.isEmpty()) return new Resource(null, null, null, null, false);
        Map<String, Object> row = rows.get(0);
        return new Resource(null, null, string(row, "mode"), string(row, "session_id"), string(row, "python_session_id") != null);
    }

    private String stageId(String experimentId, String code) {
        List<String> ids = jdbc.query("SELECT id FROM t_experiment_stage WHERE experiment_id=? AND stage_code=?",
                (rs, n) -> rs.getString(1), experimentId, code);
        if (ids.isEmpty()) throw new BusinessException(ResultCode.NOT_FOUND, "实验阶段配置不存在: " + code);
        return ids.get(0);
    }

    private static String string(Map<String, Object> row, String key) {
        Object value = row.get(key); return value == null ? null : String.valueOf(value);
    }
    private static int number(Map<String, Object> row, String key) {
        Object value = row.get(key); return value instanceof Number n ? n.intValue() : 0;
    }
    private static String waitingTutorState(int round) { return "WAITING_ROUND_" + round + "_TUTOR"; }
    private static String tutorInProgressState(int round) { return "ROUND_" + round + "_TUTOR_IN_PROGRESS"; }
    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    private static OffsetDateTime offset(Object value) {
        if (value instanceof OffsetDateTime odt) return odt;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        if (value instanceof java.time.Instant instant) return instant.atOffset(ZoneOffset.UTC);
        return null;
    }
    private record Resource(String attemptId, OffsetDateTime deadline, String mode, String sessionId, boolean initialized) {}
}
