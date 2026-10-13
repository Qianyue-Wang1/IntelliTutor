package com.intellitutor.scut.modules.experiment.service;

import cn.hutool.crypto.digest.BCrypt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.config.ExperimentProperties;
import com.intellitutor.scut.modules.experiment.domain.ExperimentGroup;
import com.intellitutor.scut.modules.experiment.domain.GlobalStage;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import com.intellitutor.scut.modules.session.entity.SessionRecord;
import com.intellitutor.scut.modules.session.service.PythonProxyService;
import com.intellitutor.scut.modules.session.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpMethod;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExperimentAdminService {
    private static final List<String> STAGES = Arrays.stream(GlobalStage.values()).map(Enum::name).toList();
    private static final List<String> LEGACY_THREE_ROUND_STAGES = List.of(
            "SETUP", "LECTURE", "PRETEST", "GROUP_ASSIGNMENT",
            "ROUND_1_TUTOR", "ROUND_1_QUIZ", "ROUND_2_TUTOR", "ROUND_2_QUIZ",
            "ROUND_3_TUTOR", "ROUND_3_QUIZ", "POSTTEST", "QUESTIONNAIRE", "FINISHED");
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$";
    private static final String INVITE_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ExperimentEventService events;
    private final ExperimentProperties properties;
    private final SessionService sessions;
    private final PythonProxyService pythonProxy;
    private final SecureRandom random = new SecureRandom();

    public void requireAdmin(String userId) {
        String roles = jdbc.queryForObject("SELECT roles FROM t_user WHERE id=? AND deleted=0", String.class, userId);
        if (roles == null || Arrays.stream(roles.split(",")).map(String::trim).noneMatch("ADMIN"::equals)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "需要实验管理员权限");
        }
    }

    @Transactional
    public ExperimentDtos.ImportResult importConfig(String adminId, ExperimentDtos.ConfigImportReq req) {
        requireAdmin(adminId);
        List<String> errors = validateConfig(req);
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> counts = Map.of("stages", req.stages().size(), "questions", req.questions().size(),
                "assessments", req.assessments().size());
        if (req.dryRun() || !errors.isEmpty()) return new ExperimentDtos.ImportResult(null, true, errors, warnings, counts);

        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM t_experiment WHERE code=?", Integer.class, req.code());
        if (existing != null && existing > 0) {
            throw new BusinessException(ResultCode.EXPERIMENT_CONFIG_FROZEN, "实验 code 已存在；请使用新的版本 code");
        }
        String experimentId = id();
        jdbc.update("INSERT INTO t_experiment(id,code,name,config_version,status,current_stage_code) VALUES(?,?,?,?,'DRAFT','SETUP')",
                experimentId, req.code(), req.name(), req.configVersion());
        for (ExperimentGroup group : ExperimentGroup.values()) {
            jdbc.update("INSERT INTO t_experiment_group(id,experiment_id,code,display_name) VALUES(?,?,?,?)",
                    id(), experimentId, group.name(), displayName(group));
        }

        Map<String, String> questionIds = new HashMap<>();
        for (var question : req.questions()) {
            String questionId = id();
            questionIds.put(question.code(), questionId);
            jdbc.update("INSERT INTO t_experiment_question(id,experiment_id,code,question_type,prompt,options,answer_key,numeric_tolerance,images,metadata) " +
                            "VALUES(?,?,?,?,?,?::jsonb,?::jsonb,?,?::jsonb,?::jsonb)",
                    questionId, experimentId, question.code(), question.type(), question.prompt(), json(question.options()),
                    json(question.answerKey()), question.numericTolerance(), json(question.images()), json(question.metadata()));
        }

        Map<String, String> assessmentIds = new HashMap<>();
        for (var assessment : req.assessments()) {
            String assessmentId = id();
            assessmentIds.put(assessment.code(), assessmentId);
            jdbc.update("INSERT INTO t_assessment(id,experiment_id,code,assessment_type,title,duration_seconds,group_code) VALUES(?,?,?,?,?,?,?)",
                    assessmentId, experimentId, assessment.code(), assessment.type(), assessment.title(),
                    assessment.durationSeconds(), assessment.groupCode());
            for (var mapping : assessment.questions()) {
                jdbc.update("INSERT INTO t_assessment_question(id,assessment_id,question_id,sequence_no,points) VALUES(?,?,?,?,?)",
                        id(), assessmentId, questionIds.get(mapping.questionCode()), mapping.sequenceNo(),
                        mapping.points() == null ? BigDecimal.ONE : mapping.points());
            }
        }

        for (var stage : req.stages()) {
            String stageId = id();
            String assessmentId = stage.assessmentCode() == null ? null : assessmentIds.get(stage.assessmentCode());
            jdbc.update("INSERT INTO t_experiment_stage(id,experiment_id,stage_code,stage_type,sequence_no,round_no,duration_seconds,title,lecture_markdown,assessment_id) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?)", stageId, experimentId, stage.code(), stage.type(), stage.sequenceNo(),
                    stage.round(), stage.durationSeconds(), stage.title(), stage.lectureMarkdown(), assessmentId);
            if (stage.groups() != null) {
                for (var group : stage.groups()) {
                    jdbc.update("INSERT INTO t_experiment_stage_group_config(id,stage_id,group_code,participating,tutor_question_id,assessment_id,mode_policy) " +
                                    "VALUES(?,?,?,?,?,?,?)", id(), stageId, group.groupCode(), group.participating(),
                            group.tutorQuestionCode() == null ? null : questionIds.get(group.tutorQuestionCode()),
                            group.assessmentCode() == null ? assessmentId : assessmentIds.get(group.assessmentCode()), group.modePolicy());
                }
            }
        }
        events.recordAdmin(adminId, experimentId, "SETUP", "EXPERIMENT_CONFIG_IMPORTED",
                Map.of("code", req.code(), "version", req.configVersion(), "counts", counts));
        return new ExperimentDtos.ImportResult(experimentId, false, List.of(), warnings, counts);
    }

    @Transactional
    public ExperimentDtos.StudentImportResult importStudents(String adminId, ExperimentDtos.StudentImportReq req) {
        requireAdmin(adminId);
        requireDraftSetup(req.experimentId());
        List<String> errors = new ArrayList<>();
        Set<String> studentIds = new HashSet<>();
        Set<String> accounts = new HashSet<>();
        for (var student : req.students()) {
            if (!studentIds.add(student.studentId())) errors.add("重复 student_id: " + student.studentId());
            if (!accounts.add(student.loginAccount())) errors.add("重复 login_account: " + student.loginAccount());
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM t_user WHERE login_account=?", Integer.class, student.loginAccount());
            if (exists != null && exists > 0) errors.add("账号已存在: " + student.loginAccount());
        }
        if (req.dryRun() || !errors.isEmpty()) return new ExperimentDtos.StudentImportResult(true, errors, List.of());

        List<ExperimentDtos.StudentCredential> credentials = new ArrayList<>();
        for (var student : req.students()) {
            String userId = id();
            String password = password();
            jdbc.update("INSERT INTO t_user(id,login_account,password,nickname,roles,must_change_password,deleted) " +
                            "VALUES(?,?,?,?, 'STUDENT',TRUE,0)", userId, student.loginAccount(),
                    BCrypt.hashpw(password, BCrypt.gensalt()), student.studentId());
            jdbc.update("INSERT INTO t_student_experiment_assignment(id,experiment_id,user_id,student_id,class_code,student_state,content_version) " +
                            "SELECT ?,?,?,?,?, 'MUST_CHANGE_PASSWORD',content_version FROM t_experiment WHERE id=?", id(), req.experimentId(), userId,
                    student.studentId(), student.classCode(), req.experimentId());
            credentials.add(new ExperimentDtos.StudentCredential(student.studentId(), student.loginAccount(), password));
        }
        events.recordAdmin(adminId, req.experimentId(), "SETUP", "STUDENTS_IMPORTED", Map.of("count", credentials.size()));
        return new ExperimentDtos.StudentImportResult(false, List.of(), credentials);
    }

    @Transactional
    public ExperimentDtos.ImportResult importGroups(String adminId, ExperimentDtos.GroupImportReq req) {
        requireAdmin(adminId);
        Map<String, Object> experiment = experimentForUpdate(req.experimentId());
        if (!"GROUP_ASSIGNMENT".equals(experiment.get("current_stage_code"))) {
            throw new BusinessException(ResultCode.EXPERIMENT_STAGE_LOCKED, "仅可在 GROUP_ASSIGNMENT 阶段导入分组");
        }
        long active = count("SELECT COUNT(*) FROM t_student_experiment_assignment WHERE experiment_id=? AND participation_status='ACTIVE'", req.experimentId());
        long pretest = count("SELECT COUNT(*) FROM t_assessment_attempt aa JOIN t_student_experiment_assignment a ON a.id=aa.assignment_id " +
                "JOIN t_experiment_stage s ON s.id=aa.stage_id WHERE a.experiment_id=? AND a.participation_status='ACTIVE' " +
                "AND s.stage_code='PRETEST' AND aa.status IN ('SUBMITTED','AUTO_SUBMITTED')", req.experimentId());
        if (active != pretest) throw new BusinessException(ResultCode.EXPERIMENT_ADVANCE_BLOCKED, "Pretest 尚未全部提交");

        List<String> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var row : req.groups()) {
            if (!Set.of("DIRECT_LLM", "BASIC_TUTOR", "FULL_TUTOR", "BLANK_CONTROL").contains(row.groupCode())) {
                errors.add("未知组别: " + row.groupCode());
            }
            String key = row.studentId() != null ? row.studentId() : row.loginAccount();
            if (key == null || !seen.add(key)) errors.add("缺少或重复学生标识: " + key);
            long matches = count("SELECT COUNT(*) FROM t_student_experiment_assignment a JOIN t_user u ON u.id=a.user_id " +
                            "WHERE a.experiment_id=? AND a.participation_status='ACTIVE' " +
                            "AND ((CAST(? AS TEXT) IS NOT NULL AND a.student_id=?) OR " +
                            "(CAST(? AS TEXT) IS NOT NULL AND u.login_account=?))",
                    req.experimentId(), row.studentId(), row.studentId(), row.loginAccount(), row.loginAccount());
            if (matches != 1) errors.add("学生标识必须唯一匹配一名 ACTIVE 学生: " + key);
        }
        if (req.groups().size() != active) errors.add("分组行数必须等于 ACTIVE 学生数 " + active);
        if (req.dryRun() || !errors.isEmpty()) return new ExperimentDtos.ImportResult(req.experimentId(), true, errors, List.of(), Map.of("groups", req.groups().size()));

        for (var row : req.groups()) {
            int updated = jdbc.update("UPDATE t_student_experiment_assignment a SET group_code=?,current_round=?,student_state=?,updated_at=NOW(),version=version+1 " +
                            "FROM t_user u WHERE a.user_id=u.id AND a.experiment_id=? AND a.participation_status='ACTIVE' " +
                            "AND ((CAST(? AS TEXT) IS NOT NULL AND a.student_id=?) OR " +
                            "(CAST(? AS TEXT) IS NOT NULL AND u.login_account=?)) AND a.group_code IS NULL",
                    row.groupCode(), "BLANK_CONTROL".equals(row.groupCode()) ? 0 : 1,
                    "BLANK_CONTROL".equals(row.groupCode()) ? "WAITING_POSTTEST" : "WAITING_ROUND_1_TUTOR",
                    req.experimentId(), row.studentId(), row.studentId(), row.loginAccount(), row.loginAccount());
            if (updated != 1) throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "无法匹配或重复分组: " + row);
            String assignedUserId = jdbc.queryForObject("SELECT a.user_id FROM t_student_experiment_assignment a JOIN t_user u ON u.id=a.user_id " +
                            "WHERE a.experiment_id=? AND ((CAST(? AS TEXT) IS NOT NULL AND a.student_id=?) OR " +
                            "(CAST(? AS TEXT) IS NOT NULL AND u.login_account=?))",
                    String.class, req.experimentId(), row.studentId(), row.studentId(), row.loginAccount(), row.loginAccount());
            events.recordSystem(assignedUserId, "GROUP_ASSIGNED", Map.of("groupCode", row.groupCode()));
        }
        events.recordAdmin(adminId, req.experimentId(), "GROUP_ASSIGNMENT", "GROUPS_ASSIGNED", Map.of("count", req.groups().size()));
        return new ExperimentDtos.ImportResult(req.experimentId(), false, List.of(), List.of(), Map.of("groups", req.groups().size()));
    }

    @Transactional
    public Map<String, Object> advance(String adminId, String experimentId, ExperimentDtos.AdvanceStageReq req) {
        requireAdmin(adminId);
        Map<String, Object> experiment = experimentForUpdate(experimentId);
        long version = ((Number) experiment.get("version")).longValue();
        if (version != req.expectedVersion()) throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "阶段已被其他管理员更新");
        GlobalStage current = GlobalStage.valueOf(String.valueOf(experiment.get("current_stage_code")));
        GlobalStage next = current.next();
        if (next == null) throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "实验已经结束");
        Completion completion = completion(experimentId, current);
        if (completion.completed() < completion.required()) {
            throw new BusinessException(ResultCode.EXPERIMENT_ADVANCE_BLOCKED,
                    "当前阶段完成 " + completion.completed() + "/" + completion.required());
        }
        if (next.tutor() && next.round() > 1) {
            long notReady = count("SELECT COUNT(*) FROM t_student_experiment_assignment a LEFT JOIN t_experiment_tutor_run r " +
                            "ON r.assignment_id=a.id AND r.round_no=? WHERE a.experiment_id=? AND a.participation_status='ACTIVE' " +
                            "AND a.group_code='FULL_TUTOR' AND (r.learner_state_status IS NULL OR r.learner_state_status<>'READY')",
                    next.round() - 1, experimentId);
            if (notReady > 0) throw new BusinessException(ResultCode.EXPERIMENT_ADVANCE_BLOCKED,
                    "仍有 " + notReady + " 名 Full Tutor 学生的前一轮 learner state 未就绪");
        }
        if (next != GlobalStage.FINISHED) {
            long configured = count("SELECT COUNT(*) FROM t_experiment_stage WHERE experiment_id=? AND stage_code=?", experimentId, next.name());
            if (configured == 0) throw new BusinessException(ResultCode.NOT_FOUND, "下一阶段未配置: " + next);
        }
        jdbc.update("UPDATE t_experiment SET current_stage_code=?,status=?,started_at=CASE WHEN ?='LECTURE' THEN COALESCE(started_at,NOW()) ELSE started_at END," +
                        "completed_at=CASE WHEN ?='FINISHED' THEN NOW() ELSE completed_at END,updated_at=NOW(),version=version+1 WHERE id=? AND version=?",
                next.name(), next == GlobalStage.FINISHED ? "FINISHED" : "ACTIVE", next.name(), next.name(), experimentId, version);
        events.recordAdmin(adminId, experimentId, next.name(), "ADMIN_STAGE_CHANGED", Map.of("from", current.name(), "to", next.name()));
        return Map.of("previousStage", current.name(), "currentStage", next.name(), "version", version + 1);
    }

    public Map<String, Object> dashboard(String adminId, String experimentId) {
        requireAdmin(adminId);
        Map<String, Object> experiment = jdbc.queryForMap("SELECT * FROM t_experiment WHERE id=?", experimentId);
        GlobalStage stage = GlobalStage.valueOf(String.valueOf(experiment.get("current_stage_code")));
        Completion completion = completion(experimentId, stage);
        List<Map<String, Object>> students = jdbc.queryForList("SELECT a.id assignment_id,a.student_id,u.login_account,a.class_code,a.group_code," +
                "a.student_state,a.current_round,a.participation_status,a.last_activity_at," +
                "(SELECT id FROM t_experiment_tutor_run r WHERE r.assignment_id=a.id ORDER BY round_no DESC LIMIT 1) tutor_run_id," +
                "(SELECT mode FROM t_experiment_tutor_run r WHERE r.assignment_id=a.id ORDER BY round_no DESC LIMIT 1) tutor_mode," +
                "(SELECT status FROM t_experiment_tutor_run r WHERE r.assignment_id=a.id ORDER BY round_no DESC LIMIT 1) tutor_status," +
                "(SELECT learner_state_status FROM t_experiment_tutor_run r WHERE r.assignment_id=a.id ORDER BY round_no DESC LIMIT 1) learner_state_status," +
                "COALESCE((SELECT jsonb_object_agg(s.stage_code,aa.status)::text FROM t_assessment_attempt aa " +
                "JOIN t_experiment_stage s ON s.id=aa.stage_id WHERE aa.assignment_id=a.id),'{}') assessment_statuses " +
                "FROM t_student_experiment_assignment a JOIN t_user u ON u.id=a.user_id WHERE a.experiment_id=? ORDER BY a.student_id", experimentId);
        List<Map<String, Object>> anomalies = jdbc.queryForList("SELECT a.id assignment_id,a.student_id,u.login_account,a.student_state,a.last_activity_at," +
                "CASE WHEN EXISTS(SELECT 1 FROM t_experiment_tutor_run r WHERE r.assignment_id=a.id AND r.learner_state_status='FAILED') THEN 'LEARNER_STATE_FAILED' " +
                "WHEN a.student_state LIKE '%TUTOR_IN_PROGRESS' AND NOT EXISTS(SELECT 1 FROM t_experiment_tutor_run r WHERE r.assignment_id=a.id AND r.status='IN_PROGRESS') THEN 'STATE_INCONSISTENT' " +
                "WHEN a.student_state LIKE '%ASSESSMENT_IN_PROGRESS' THEN 'ASSESSMENT_UNSUBMITTED' " +
                "WHEN a.student_state LIKE '%QUIZ_IN_PROGRESS' OR a.student_state IN ('PRETEST_IN_PROGRESS','POSTTEST_IN_PROGRESS','QUESTIONNAIRE_IN_PROGRESS') THEN 'ASSESSMENT_UNSUBMITTED' " +
                "WHEN a.student_state LIKE '%TUTOR_IN_PROGRESS' AND a.last_activity_at < NOW()-(? * INTERVAL '1 minute') THEN 'TUTOR_INTERRUPTED' " +
                "WHEN a.student_state<>'EXPERIMENT_FINISHED' AND a.last_activity_at < NOW()-(? * INTERVAL '1 minute') THEN 'LONG_INACTIVE' END anomaly_type " +
                "FROM t_student_experiment_assignment a JOIN t_user u ON u.id=a.user_id WHERE a.experiment_id=? AND a.participation_status='ACTIVE' " +
                "AND (EXISTS(SELECT 1 FROM t_experiment_tutor_run r WHERE r.assignment_id=a.id AND r.learner_state_status='FAILED') " +
                "OR (a.student_state LIKE '%TUTOR_IN_PROGRESS' AND NOT EXISTS(SELECT 1 FROM t_experiment_tutor_run r WHERE r.assignment_id=a.id AND r.status='IN_PROGRESS')) " +
                "OR a.student_state LIKE '%QUIZ_IN_PROGRESS' OR a.student_state IN ('PRETEST_IN_PROGRESS','POSTTEST_IN_PROGRESS','QUESTIONNAIRE_IN_PROGRESS') " +
                "OR (a.last_activity_at IS NOT NULL AND a.last_activity_at < NOW()-(? * INTERVAL '1 minute'))) ORDER BY a.last_activity_at NULLS FIRST",
                properties.getInactivityMinutes(), properties.getInactivityMinutes(), experimentId, properties.getInactivityMinutes());
        return Map.of("experiment", experiment, "completion", Map.of("required", completion.required(), "completed", completion.completed()),
                "nextStage", stage.next() == null ? "" : stage.next().name(), "students", students, "anomalies", anomalies);
    }

    public String exportResults(String adminId, String experimentId) {
        requireAdmin(adminId);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.student_id,u.login_account,a.class_code,a.group_code," +
                "s.stage_code,ass.code assessment_code,aa.status,aa.started_at,aa.deadline_at,aa.submitted_at,aa.final_score,aa.max_score " +
                "FROM t_student_experiment_assignment a JOIN t_user u ON u.id=a.user_id " +
                "LEFT JOIN t_assessment_attempt aa ON aa.assignment_id=a.id LEFT JOIN t_experiment_stage s ON s.id=aa.stage_id " +
                "LEFT JOIN t_assessment ass ON ass.id=aa.assessment_id WHERE a.experiment_id=? ORDER BY a.student_id,s.sequence_no", experimentId);
        String[] columns = {"student_id","login_account","class_code","group_code","stage_code","assessment_code","status","started_at","deadline_at","submitted_at","final_score","max_score"};
        return csv(rows, columns);
    }

    public String exportTutorRuns(String adminId, String experimentId) {
        requireAdmin(adminId);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.student_id,u.login_account,a.group_code,r.round_no,r.mode,r.status," +
                "r.session_id,r.learner_state_status,r.started_at,r.completed_at FROM t_student_experiment_assignment a " +
                "JOIN t_user u ON u.id=a.user_id LEFT JOIN t_experiment_tutor_run r ON r.assignment_id=a.id " +
                "WHERE a.experiment_id=? ORDER BY a.student_id,r.round_no", experimentId);
        String[] columns = {"student_id","login_account","group_code","round_no","mode","status","session_id","learner_state_status","started_at","completed_at"};
        return csv(rows, columns);
    }

    public String exportEvents(String adminId, String experimentId) {
        requireAdmin(adminId);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT event_id,client_event_id,experiment_id,assignment_id,actor_user_id,subject_user_id," +
                "experiment_group,round_no,experiment_stage,tutor_mode,question_id,session_id,event_type,event_data::text event_data," +
                "client_timestamp,server_timestamp FROM t_experiment_event_log WHERE experiment_id=? ORDER BY server_timestamp,event_id", experimentId);
        StringBuilder jsonl = new StringBuilder();
        for (Map<String, Object> row : rows) {
            Object rawData = row.get("event_data");
            if (rawData instanceof String value) {
                try { row.put("event_data", objectMapper.readTree(value)); } catch (Exception ignored) { }
            }
            try { jsonl.append(objectMapper.writeValueAsString(row)).append('\n'); }
            catch (Exception e) { throw new BusinessException(ResultCode.INTERNAL_ERROR, "事件导出失败"); }
        }
        return jsonl.toString();
    }

    public List<Map<String, Object>> listExperiments(String adminId) {
        requireAdmin(adminId);
        return jdbc.queryForList("SELECT id,code,name,config_version,status,current_stage_code,version,created_at,started_at,completed_at " +
                "FROM t_experiment ORDER BY created_at DESC");
    }

    @Transactional
    public void participation(String adminId, String experimentId, String status, ExperimentDtos.MutationReasonReq req) {
        requireAdmin(adminId);
        if (!Set.of("WITHDRAWN", "EXCLUDED").contains(status)) throw new BusinessException(ResultCode.PARAM_ERROR);
        int updated = jdbc.update("UPDATE t_student_experiment_assignment SET participation_status=?,participation_reason=?,updated_at=NOW(),version=version+1 " +
                "WHERE id=? AND experiment_id=? AND participation_status='ACTIVE'", status, req.reason(), req.assignmentId(), experimentId);
        if (updated != 1) throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT);
        events.recordAdmin(adminId, experimentId, currentStage(experimentId), "STUDENT_" + status,
                Map.of("assignmentId", req.assignmentId(), "reason", req.reason()));
    }

    @Transactional
    public ExperimentDtos.ResetPasswordVO resetPassword(String adminId, String experimentId, ExperimentDtos.ResetPasswordReq req) {
        requireAdmin(adminId);
        Map<String, Object> row = jdbc.queryForMap("SELECT a.user_id,u.login_account FROM t_student_experiment_assignment a JOIN t_user u ON u.id=a.user_id " +
                "WHERE a.id=? AND a.experiment_id=?", req.assignmentId(), experimentId);
        String password = password();
        jdbc.update("UPDATE t_user SET password=?,must_change_password=TRUE,password_changed_at=NULL,updated_at=NOW() WHERE id=?",
                BCrypt.hashpw(password, BCrypt.gensalt()), row.get("user_id"));
        jdbc.update("UPDATE t_student_experiment_assignment SET password_resume_state=COALESCE(password_resume_state,student_state)," +
                        "student_state='MUST_CHANGE_PASSWORD',updated_at=NOW(),version=version+1 WHERE id=?",
                req.assignmentId());
        events.recordAdmin(adminId, experimentId, currentStage(experimentId), "TEMPORARY_PASSWORD_RESET", Map.of("assignmentId", req.assignmentId()));
        return new ExperimentDtos.ResetPasswordVO(String.valueOf(row.get("login_account")), password);
    }

    @Transactional
    public ExperimentDtos.InviteCodeVO createInviteCode(String adminId, String experimentId) {
        requireAdmin(adminId);
        Map<String, Object> experiment = jdbc.queryForMap("SELECT status,registration_policy FROM t_experiment WHERE id=?", experimentId);
        if (!"ACTIVE".equals(experiment.get("status")) || !allowsInvite(String.valueOf(experiment.get("registration_policy")))) {
            throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "当前实验未开放邀请码报名");
        }
        String code = inviteCode();
        jdbc.update("INSERT INTO t_experiment_invite_code(id,experiment_id,code_hash,max_uses) VALUES(?,?,?,1)",
                id(), experimentId, BCrypt.hashpw(code, BCrypt.gensalt()));
        events.recordAdmin(adminId, experimentId, currentStage(experimentId), "INVITE_CODE_CREATED", Map.of("maxUses", 1));
        return new ExperimentDtos.InviteCodeVO(code);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> retryPersonalization(String adminId, String experimentId, ExperimentDtos.PersonalizationRetryReq req) {
        requireAdmin(adminId);
        Map<String, Object> run = jdbc.queryForMap("SELECT r.*,a.user_id FROM t_experiment_tutor_run r " +
                "JOIN t_student_experiment_assignment a ON a.id=r.assignment_id WHERE r.id=? AND a.experiment_id=?",
                req.tutorRunId(), experimentId);
        if (!"FULL_TUTOR".equals(run.get("group_code_snapshot")) || !"COMPLETED".equals(run.get("status"))) {
            throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "只能重试已完成 Full Tutor 会话的 learner state");
        }
        String sessionId = String.valueOf(run.get("session_id"));
        SessionRecord session = sessions.getSessionById(sessionId);
        if (session == null) throw new BusinessException(ResultCode.SESSION_NOT_EXIST);
        if (session.getPythonSessionId() == null || session.getPythonSessionId().isBlank())
            throw new BusinessException(ResultCode.SESSION_STATE_CONFLICT, "Python Tutor 会话尚未建立");
        jdbc.update("UPDATE t_experiment_tutor_run SET learner_state_status='PENDING',version=version+1 WHERE id=?", req.tutorRunId());
        events.recordAdmin(adminId, experimentId, currentStage(experimentId), "PERSONALIZATION_RETRY_STARTED", Map.of("tutorRunId", req.tutorRunId()));
        try {
            Map<String, Object> response = pythonProxy.proxyWithSessionRecovery(session, String.valueOf(run.get("mode")),
                    "/api/v1/learnerstate/retry", HttpMethod.POST, Map.of("session_id", session.getPythonSessionId()));
            Map<String, Object> data = response.get("data") instanceof Map<?, ?> value ? (Map<String, Object>) value : Map.of();
            String status = "READY".equals(String.valueOf(data.get("learner_state_status"))) ? "READY" : "FAILED";
            jdbc.update("UPDATE t_experiment_tutor_run SET learner_state_status=?,version=version+1 WHERE id=?", status, req.tutorRunId());
            events.recordSystem(String.valueOf(run.get("user_id")), "PERSONALIZATION_RETRY_" + status,
                    Map.of("runId", req.tutorRunId()));
            return Map.of("tutorRunId", req.tutorRunId(), "status", status,
                    "error", String.valueOf(data.getOrDefault("learner_state_error", "")));
        } catch (Exception error) {
            jdbc.update("UPDATE t_experiment_tutor_run SET learner_state_status='FAILED',version=version+1 WHERE id=?", req.tutorRunId());
            events.recordSystem(String.valueOf(run.get("user_id")), "PERSONALIZATION_RETRY_FAILED", Map.of("runId", req.tutorRunId()));
            throw error;
        }
    }

    private List<String> validateConfig(ExperimentDtos.ConfigImportReq req) {
        List<String> errors = new ArrayList<>();
        List<String> actual = req.stages().stream().sorted(Comparator.comparingInt(ExperimentDtos.StageImport::sequenceNo))
                .map(ExperimentDtos.StageImport::code).toList();
        if (!isSupportedStageSequence(actual)) errors.add("阶段必须严格为五轮流程，或已有的三轮兼容流程");
        Set<String> questionCodes = req.questions().stream().map(ExperimentDtos.QuestionImport::code).collect(Collectors.toSet());
        Set<String> assessmentCodes = req.assessments().stream().map(ExperimentDtos.AssessmentImport::code).collect(Collectors.toSet());
        if (questionCodes.size() != req.questions().size()) errors.add("question code 必须唯一");
        if (assessmentCodes.size() != req.assessments().size()) errors.add("assessment code 必须唯一");
        Map<String, ExperimentDtos.QuestionImport> questions = req.questions().stream()
                .collect(Collectors.toMap(ExperimentDtos.QuestionImport::code, Function.identity(), (a, b) -> a));
        for (var question : req.questions()) {
            if (!Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE", "NUMERIC", "LIKERT", "TUTOR").contains(question.type()))
                errors.add("未知题型: " + question.type());
        }
        for (var assessment : req.assessments()) {
            if (!Set.of("PRETEST", "QUIZ", "POSTTEST", "QUESTIONNAIRE").contains(assessment.type()))
                errors.add("未知 Assessment 类型: " + assessment.type());
            Set<Integer> sequence = new HashSet<>();
            for (var mapping : assessment.questions()) {
                if (!sequence.add(mapping.sequenceNo())) errors.add("Assessment 题序重复: " + assessment.code() + "/" + mapping.sequenceNo());
                if (!questionCodes.contains(mapping.questionCode())) errors.add("Assessment 引用了不存在的题目: " + mapping.questionCode());
                var question = questions.get(mapping.questionCode());
                if (question != null && !"QUESTIONNAIRE".equals(assessment.type()) && question.answerKey() == null)
                    errors.add("计分题缺少 answer_key: " + mapping.questionCode());
            }
        }
        for (var stage : req.stages()) {
            if (!Set.of("CONTROL", "LECTURE", "ASSESSMENT", "TUTOR").contains(stage.type())) errors.add("未知阶段类型: " + stage.type());
            if (stage.assessmentCode() != null && !assessmentCodes.contains(stage.assessmentCode())) errors.add("阶段引用未知 Assessment: " + stage.assessmentCode());
            if ("LECTURE".equals(stage.code()) && (stage.lectureMarkdown() == null || stage.lectureMarkdown().isBlank())) errors.add("Lecture 内容不能为空");
            Set<String> stageGroups = new HashSet<>();
            if (stage.groups() != null) for (var group : stage.groups()) {
                try { ExperimentGroup.valueOf(group.groupCode()); } catch (Exception e) { errors.add("未知实验组: " + group.groupCode()); }
                if (!stageGroups.add(group.groupCode())) errors.add("阶段组别配置重复: " + stage.code() + "/" + group.groupCode());
                if (group.tutorQuestionCode() != null && !questionCodes.contains(group.tutorQuestionCode())) errors.add("Tutor 引用未知题目: " + group.tutorQuestionCode());
                if (group.assessmentCode() != null && !assessmentCodes.contains(group.assessmentCode())) errors.add("组别引用未知 Assessment: " + group.assessmentCode());
                if (stage.code().endsWith("_TUTOR") && group.participating() && group.tutorQuestionCode() == null)
                    errors.add("参与 Tutor 的组别缺少题目: " + stage.code() + "/" + group.groupCode());
            }
            if (stage.code().endsWith("_TUTOR") && !stageGroups.equals(Arrays.stream(ExperimentGroup.values()).map(Enum::name).collect(Collectors.toSet())))
                errors.add("Tutor 阶段必须配置全部四个组别: " + stage.code());
        }
        return errors;
    }

    static boolean isSupportedStageSequence(List<String> stages) {
        return STAGES.equals(stages) || LEGACY_THREE_ROUND_STAGES.equals(stages);
    }

    private Completion completion(String experimentId, GlobalStage stage) {
        long required = count("SELECT COUNT(*) FROM t_student_experiment_assignment WHERE experiment_id=? AND participation_status='ACTIVE'" +
                ((stage.tutor() || stage.quiz()) ? " AND group_code<>'BLANK_CONTROL'" : ""), experimentId);
        long completed;
        if (stage == GlobalStage.SETUP) {
            completed = count("SELECT COUNT(*) FROM t_student_experiment_assignment WHERE experiment_id=? AND participation_status='ACTIVE'", experimentId);
            if (completed == 0) required = 1;
        } else if (stage == GlobalStage.GROUP_ASSIGNMENT) {
            completed = count("SELECT COUNT(*) FROM t_student_experiment_assignment WHERE experiment_id=? AND participation_status='ACTIVE' AND group_code IS NOT NULL", experimentId);
        } else if (stage.tutor()) {
            completed = count("SELECT COUNT(*) FROM t_experiment_tutor_run r JOIN t_student_experiment_assignment a ON a.id=r.assignment_id " +
                    "WHERE a.experiment_id=? AND a.participation_status='ACTIVE' AND a.group_code<>'BLANK_CONTROL' AND r.round_no=? AND r.status='COMPLETED'", experimentId, stage.round());
        } else if (stage == GlobalStage.FINISHED) {
            completed = required;
        } else {
            completed = count("SELECT COUNT(*) FROM t_student_stage_progress p JOIN t_student_experiment_assignment a ON a.id=p.assignment_id " +
                    "JOIN t_experiment_stage s ON s.id=p.stage_id WHERE a.experiment_id=? AND a.participation_status='ACTIVE' " +
                    ((stage.quiz()) ? "AND a.group_code<>'BLANK_CONTROL' " : "") + "AND s.stage_code=? AND p.status='COMPLETED'", experimentId, stage.name());
        }
        return new Completion(required, completed);
    }

    private void requireDraftSetup(String experimentId) {
        Map<String, Object> experiment = jdbc.queryForMap("SELECT status,current_stage_code FROM t_experiment WHERE id=?", experimentId);
        if (!"DRAFT".equals(experiment.get("status")) || !"SETUP".equals(experiment.get("current_stage_code")))
            throw new BusinessException(ResultCode.EXPERIMENT_CONFIG_FROZEN);
    }
    private Map<String, Object> experimentForUpdate(String experimentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM t_experiment WHERE id=? FOR UPDATE", experimentId);
        if (rows.isEmpty()) throw new BusinessException(ResultCode.NOT_FOUND, "实验不存在");
        return rows.get(0);
    }
    private String currentStage(String experimentId) { return jdbc.queryForObject("SELECT current_stage_code FROM t_experiment WHERE id=?", String.class, experimentId); }
    private long count(String sql, Object... args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 0 : value; }
    private String json(Object value) { try { return value == null ? "null" : objectMapper.writeValueAsString(value); } catch (Exception e) { throw new BusinessException(ResultCode.PARAM_ERROR, "配置 JSON 无效"); } }
    private String csv(List<Map<String, Object>> rows, String[] columns) {
        StringBuilder result = new StringBuilder("\uFEFF").append(String.join(",", columns)).append("\r\n");
        for (Map<String, Object> row : rows) {
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) result.append(',');
                String value = row.get(columns[i]) == null ? "" : String.valueOf(row.get(columns[i]));
                result.append('"').append(value.replace("\"", "\"\"")).append('"');
            }
            result.append("\r\n");
        }
        return result.toString();
    }
    private String password() { StringBuilder value = new StringBuilder(); for (int i=0;i<14;i++) value.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length()))); return value.toString(); }
    private String inviteCode() { StringBuilder value = new StringBuilder(10); for (int i=0;i<10;i++) value.append(INVITE_CODE_ALPHABET.charAt(random.nextInt(INVITE_CODE_ALPHABET.length()))); return value.toString(); }
    private static boolean allowsInvite(String policy) { return "SMS_OR_INVITE".equals(policy) || "BOTH".equals(policy) || "INVITE_CODE".equals(policy); }
    private static String id() { return UUID.randomUUID().toString(); }
    private static String displayName(ExperimentGroup group) { return switch (group) { case DIRECT_LLM -> "Direct LLM"; case BASIC_TUTOR -> "Basic Tutor"; case FULL_TUTOR -> "Full Tutor"; case BLANK_CONTROL -> "Blank Control"; }; }
    private record Completion(long required, long completed) {}
}
