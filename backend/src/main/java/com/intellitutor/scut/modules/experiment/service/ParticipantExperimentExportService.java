package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ParticipantExperimentExportService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public List<ExperimentDtos.LocalRecordVO> records(String userId) {
        return jdbc.queryForList("SELECT a.id,a.selected_mode,a.student_state,a.started_at,a.completed_at,a.created_at,p.participant_name,p.phone_number " +
                "FROM t_local_experiment_participant current_p JOIN t_student_experiment_assignment current_a ON current_a.id=current_p.assignment_id " +
                "JOIN t_local_experiment_participant p ON p.phone_number=current_p.phone_number JOIN t_student_experiment_assignment a ON a.id=p.assignment_id " +
                "WHERE current_a.user_id=? ORDER BY COALESCE(a.started_at,a.created_at) DESC", userId).stream().map(row -> new ExperimentDtos.LocalRecordVO(
                text(row, "id"), text(row, "participant_name"), text(row, "phone_number"), text(row, "selected_mode"),
                text(row, "student_state"), firstTime(row, "started_at", "created_at"), value(row, "completed_at"), "EXPERIMENT_FINISHED".equals(text(row, "student_state")))).toList();
    }

    public byte[] exportWorkbook(String userId, String assignmentId) {
        Map<String, Object> assignment = completedOwnedRecord(userId, assignmentId);
        List<Map<String, Object>> assessmentResponses = jdbc.queryForList("SELECT CASE WHEN ca.assessment_code='PRETEST' THEN 'PRETEST' WHEN ca.assessment_code='POSTTEST' THEN 'POSTTEST' " +
                "WHEN ca.assessment_code ~ '^Q[1-5]$' THEN 'ROUND_' || substring(ca.assessment_code from 2) || '_QUIZ' ELSE ca.assessment_code END experiment_stage," +
                "CASE WHEN ca.assessment_code ~ '^Q[1-5]$' THEN substring(ca.assessment_code from 2)::integer ELSE 0 END round_no," +
                "CASE WHEN ca.assessment_code ~ '^Q[1-5]$' THEN a.selected_mode ELSE NULL END tutor_mode," +
                "ca.assessment_code,ca.status assessment_status,ca.started_at attempt_started_at,ca.deadline_at,ca.submitted_at attempt_submitted_at," +
                "ca.final_score,ca.max_score,cr.question_code,cr.answer selected_answer,cr.revision,cr.first_viewed_at,cr.first_answered_at,cr.last_answered_at," +
                "cr.change_count,cr.answer_changed,cr.submitted_at response_submitted_at,cr.duration_ms,cr.is_correct,cr.score " +
                "FROM t_content_assessment_attempt ca JOIN t_student_experiment_assignment a ON a.id=ca.assignment_id " +
                "LEFT JOIN t_content_assessment_response cr ON cr.attempt_id=ca.id WHERE ca.assignment_id=? " +
                "AND (a.content_version <> 'unsupervised-learning-v2' OR ca.assessment_code <> 'PRETEST') " +
                "ORDER BY round_no,ca.assessment_code,cr.question_code", assignmentId);
        List<Map<String, Object>> tutorSteps = jdbc.queryForList("SELECT round_no,learning_mode,step_key,submission_type,submitted_answer,correctness,retry_count,answered_at " +
                "FROM t_experiment_tutor_step_record WHERE assignment_id=? ORDER BY answered_at", assignmentId);
        List<Map<String, Object>> interactions = jdbc.queryForList("SELECT r.round_no,r.group_code_snapshot,r.mode,r.status,r.interaction_count,r.started_at,r.completed_at," +
                "m.role,m.message_type,CASE WHEN m.role='student' THEN m.content ELSE NULL END student_message,m.correctness,m.client_timestamp,m.server_timestamp " +
                "FROM t_experiment_tutor_run r LEFT JOIN t_tutor_message m ON m.tutor_run_id=r.id WHERE r.assignment_id=? ORDER BY r.round_no,m.server_timestamp", assignmentId);
        List<Map<String, Object>> eventRows = jdbc.queryForList("SELECT event_id,client_event_id,round_no,experiment_stage,tutor_mode,question_id,session_id,event_type," +
                "event_data::text event_data,client_timestamp,server_timestamp FROM t_experiment_event_log WHERE assignment_id=? ORDER BY server_timestamp,event_id", assignmentId);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writeSheet(workbook, "参与者信息", List.of(participantRow(assignment)));
            writeSheet(workbook, "测试题目", List.of(Map.of("assessment_items_json", assessmentItems(text(assignment, "package_json")))));
            writeSheet(workbook, "测试作答", assessmentResponses, List.of("experiment_stage", "round_no", "tutor_mode", "assessment_code", "assessment_status",
                    "attempt_started_at", "deadline_at", "attempt_submitted_at", "final_score", "max_score", "question_code", "selected_answer",
                    "revision", "first_viewed_at", "first_answered_at", "last_answered_at", "change_count", "answer_changed", "response_submitted_at",
                    "duration_ms", "is_correct", "score"));
            writeSheet(workbook, "子步骤作答", tutorSteps, List.of("round_no", "learning_mode", "step_key", "submission_type",
                    "submitted_answer", "correctness", "retry_count", "answered_at"));
            writeSheet(workbook, "学生交互", interactions, List.of("round_no", "group_code_snapshot", "mode", "status",
                    "interaction_count", "started_at", "completed_at", "role", "message_type", "student_message",
                    "correctness", "client_timestamp", "server_timestamp"));
            writeSheet(workbook, "行为事件", eventRows);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) { throw new BusinessException(ResultCode.INTERNAL_ERROR, "实验数据导出失败", exception); }
    }

    private Map<String, Object> completedOwnedRecord(String userId, String assignmentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.id assignment_id,p.participant_name,p.phone_number,a.selected_mode,a.student_state,a.started_at,a.created_at,a.completed_at," +
                "a.content_version,cv.package_json::text package_json FROM t_local_experiment_participant current_p " +
                "JOIN t_student_experiment_assignment current_a ON current_a.id=current_p.assignment_id " +
                "JOIN t_local_experiment_participant p ON p.phone_number=current_p.phone_number JOIN t_student_experiment_assignment a ON a.id=p.assignment_id " +
                "JOIN t_experiment_content_version cv ON cv.experiment_id=a.experiment_id AND cv.version=a.content_version " +
                "WHERE current_a.user_id=? AND a.id=? AND a.student_state='EXPERIMENT_FINISHED'", userId, assignmentId);
        if (rows.isEmpty()) throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "仅可导出已完成的本地实验记录");
        return rows.get(0);
    }

    private String assessmentItems(String packageJson) {
        try {
            JsonNode assessments = objectMapper.readTree(packageJson).path("assessments").deepCopy();
            removeAnswerKeys(assessments);
            return objectMapper.writeValueAsString(assessments);
        } catch (Exception exception) { throw new BusinessException(ResultCode.INTERNAL_ERROR, "导出题目失败", exception); }
    }

    private Map<String, Object> participantRow(Map<String, Object> assignment) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String key : List.of("assignment_id", "participant_name", "phone_number", "selected_mode", "student_state", "completed_at", "content_version")) {
            row.put(key, assignment.get(key));
        }
        row.put("started_at", firstTime(assignment, "started_at", "created_at"));
        return row;
    }

    private void removeAnswerKeys(JsonNode node) {
        if (node.isObject()) { ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("answerKey"); node.forEach(this::removeAnswerKeys); }
        else if (node.isArray()) node.forEach(this::removeAnswerKeys);
    }

    private void writeSheet(XSSFWorkbook workbook, String title, List<Map<String, Object>> rows) {
        writeSheet(workbook, title, rows, List.of());
    }

    private void writeSheet(XSSFWorkbook workbook, String title, List<Map<String, Object>> rows, List<String> emptyColumns) {
        var sheet = workbook.createSheet(title);
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        rows.forEach(row -> row.keySet().stream().sorted(Comparator.naturalOrder()).forEach(columns::add));
        if (columns.isEmpty()) columns.addAll(emptyColumns);
        var header = sheet.createRow(0);
        List<String> names = new ArrayList<>(columns);
        for (int index = 0; index < names.size(); index++) header.createCell(index).setCellValue(names.get(index));
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            var row = sheet.createRow(rowIndex + 1);
            for (int column = 0; column < names.size(); column++) {
                Object value = rows.get(rowIndex).get(names.get(column));
                if (value instanceof Number number) row.createCell(column).setCellValue(number.doubleValue());
                else if (value != null) row.createCell(column).setCellValue(String.valueOf(value));
            }
        }
    }
    private static String text(Map<String, Object> row, String key) { Object value = row.get(key); return value == null ? null : String.valueOf(value); }
    private static OffsetDateTime value(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof OffsetDateTime time) return time;
        if (value instanceof Timestamp time) return time.toInstant().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        return null;
    }
    private static OffsetDateTime firstTime(Map<String, Object> row, String primary, String fallback) {
        OffsetDateTime value = value(row, primary);
        return value == null ? value(row, fallback) : value;
    }
}
