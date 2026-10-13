package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.config.ExperimentProperties;
import com.intellitutor.scut.modules.experiment.domain.GlobalStage;
import com.intellitutor.scut.modules.experiment.domain.StudentState;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExperimentEventService {
    private static final Set<String> CLIENT_EVENTS = Set.of(
            "PAGE_ENTER", "PAGE_LEAVE", "PAGE_FOCUS", "PAGE_BLUR", "PAGE_SCROLL",
            "PASTE", "CLICK", "CONTROL_CLICK", "TYPING_METRICS", "QUESTION_VIEWED", "ANSWER_SELECTED", "ANSWER_CHANGED");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ExperimentProperties properties;

    public void recordSystem(String userId, String eventType, Map<String, ?> data) {
        AssignmentContext context = contextForUser(userId);
        if (context == null) throw new BusinessException(ResultCode.EXPERIMENT_NOT_ASSIGNED);
        insert(context, userId, userId, null, eventType, data, null, null, null);
    }

    public void recordUserAction(String userId, String eventType, Map<String, ?> data,
                                 OffsetDateTime clientTime, String questionId, String sessionId) {
        AssignmentContext context = contextForUser(userId);
        if (context == null) throw new BusinessException(ResultCode.EXPERIMENT_NOT_ASSIGNED);
        insert(context, userId, userId, null, eventType, data, sanitizeClientTime(clientTime), questionId, sessionId);
    }

    public void recordAdmin(String adminUserId, String experimentId, String stage, String eventType, Map<String, ?> data) {
        String json = json(data);
        jdbc.update("INSERT INTO t_experiment_event_log(event_id,experiment_id,actor_user_id,experiment_stage,event_type,event_data,server_timestamp) " +
                        "VALUES(?,?,?,?,?,?::jsonb,NOW())",
                UUID.randomUUID().toString(), experimentId, adminUserId, stage, eventType, json);
    }

    public void recordClientBatch(String userId, ExperimentDtos.EventBatchReq request) {
        if (request.events().size() > properties.getEventBatchSize()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "事件批次过大");
        }
        AssignmentContext context = contextForUser(userId);
        if (context == null) throw new BusinessException(ResultCode.EXPERIMENT_NOT_ASSIGNED);
        for (ExperimentDtos.EventReq event : request.events()) {
            if (!CLIENT_EVENTS.contains(event.eventType())) {
                throw new BusinessException(ResultCode.FORBIDDEN, "不允许客户端上报事件 " + event.eventType());
            }
            String payload = event.data() == null ? "{}" : event.data().toString();
            if (payload.getBytes(StandardCharsets.UTF_8).length > properties.getEventPayloadBytes()) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "事件内容过大");
            }
            OffsetDateTime clientTime = sanitizeClientTime(event.clientTimestamp());
            insert(context, userId, userId, event.clientEventId(), event.eventType(),
                    event.data(), clientTime, event.questionId(), event.sessionId());
        }
        jdbc.update("UPDATE t_student_experiment_assignment SET last_activity_at=NOW(),updated_at=NOW() WHERE id=?",
                context.assignmentId());
    }

    @Scheduled(fixedDelay = 60_000)
    public void recordInterruptedSessions() {
        var users = jdbc.query("SELECT a.user_id FROM t_student_experiment_assignment a WHERE a.participation_status='ACTIVE' " +
                        "AND a.student_state LIKE '%_IN_PROGRESS' AND a.last_activity_at IS NOT NULL " +
                        "AND a.last_activity_at < NOW()-(? * INTERVAL '1 minute') AND NOT EXISTS(" +
                        "SELECT 1 FROM t_experiment_event_log l WHERE l.assignment_id=a.id AND l.event_type='SESSION_INTERRUPTED' " +
                        "AND l.server_timestamp>=a.last_activity_at)",
                (rs, n) -> rs.getString(1), properties.getInactivityMinutes());
        for (String userId : users) {
            try { recordSystem(userId, "SESSION_INTERRUPTED", Map.of("reason", "INACTIVITY_TIMEOUT")); }
            catch (Exception ignored) { }
        }
    }

    private void insert(AssignmentContext context, String actor, String subject, String clientEventId,
                        String type, Object data, OffsetDateTime clientTime, String questionId, String sessionId) {
        try {
            jdbc.update("INSERT INTO t_experiment_event_log(event_id,client_event_id,experiment_id,assignment_id,actor_user_id,subject_user_id," +
                            "experiment_group,round_no,experiment_stage,tutor_mode,question_id,session_id,event_type,event_data,client_timestamp,server_timestamp) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,NOW())",
                    UUID.randomUUID().toString(), clientEventId, context.experimentId(), context.assignmentId(), actor, subject,
                    context.groupCode(), context.round(), context.stage(), context.mode(), questionId, sessionId, type,
                    json(data), clientTime);
        } catch (DuplicateKeyException ignored) {
            // Client event UUIDs are deliberately idempotent.
        }
    }

    public AssignmentContext contextForUser(String userId) {
        return jdbc.query("SELECT a.id,a.experiment_id,a.group_code,a.current_round,a.student_state,a.selected_mode," +
                        "(SELECT r.mode FROM t_experiment_tutor_run r WHERE r.assignment_id=a.id ORDER BY r.round_no DESC LIMIT 1) mode " +
                        "FROM t_student_experiment_assignment a " +
                        "WHERE a.user_id=? ORDER BY a.created_at DESC LIMIT 1",
                rs -> {
                    if (!rs.next()) return null;
                    EventMetadata metadata = eventMetadata(
                            StudentState.valueOf(rs.getString("student_state")),
                            rs.getInt("current_round"),
                            rs.getString("mode"),
                            rs.getString("selected_mode")
                    );
                    return new AssignmentContext(rs.getString("id"), rs.getString("experiment_id"),
                            rs.getString("group_code"), metadata.round(), metadata.stage(), metadata.tutorMode());
                }, userId);
    }

    static EventMetadata eventMetadata(StudentState state, int currentRound, String tutorMode, String selectedMode) {
        String stateName = state.name();
        if (stateName.contains("PRETEST")) return new EventMetadata(GlobalStage.PRETEST.name(), 0, null);
        if (stateName.contains("POSTTEST")) return new EventMetadata(GlobalStage.POSTTEST.name(), 0, null);

        if (stateName.matches("(?:WAITING_)?ROUND_[1-5]_(?:TUTOR|QUIZ)(?:_IN_PROGRESS)?")) {
            GlobalStage stage = GlobalStage.valueOf(stateName.replace("WAITING_", "").replace("_IN_PROGRESS", ""));
            String mode = tutorMode != null ? tutorMode : ("NO_AI".equals(selectedMode) ? "no_ai" : null);
            return new EventMetadata(stage.name(), stage.round(), mode);
        }

        if (stateName.contains("LECTURE")) return new EventMetadata(GlobalStage.LECTURE.name(), 0, null);
        if (state == StudentState.WAITING_GROUP_ASSIGNMENT || state == StudentState.WAITING_MODE_SELECTION) {
            return new EventMetadata(GlobalStage.GROUP_ASSIGNMENT.name(), 0, null);
        }
        if (state == StudentState.WAITING_CREDENTIAL || state == StudentState.EXPERIMENT_FINISHED) {
            return new EventMetadata(GlobalStage.FINISHED.name(), 0, null);
        }
        return new EventMetadata(GlobalStage.SETUP.name(), currentRound, null);
    }

    private OffsetDateTime sanitizeClientTime(OffsetDateTime clientTime) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return clientTime != null && Math.abs(clientTime.toEpochSecond() - now.toEpochSecond()) <= 86_400
                ? clientTime : null;
    }

    private String json(Object value) {
        try { return value == null ? "{}" : objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessException(ResultCode.PARAM_ERROR, "事件内容不是有效 JSON"); }
    }

    public record AssignmentContext(String assignmentId, String experimentId, String groupCode,
                                    int round, String stage, String mode) {}
    record EventMetadata(String stage, int round, String tutorMode) {}
}
