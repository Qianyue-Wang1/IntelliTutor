package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class ExperimentExportService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ExperimentAdminService admin;

    public byte[] exportZip(String adminId, String experimentId) {
        admin.requireAdmin(adminId);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("participants.csv", csv(jdbc.queryForList("SELECT a.id assignment_id,a.student_id,u.nickname name,u.phone_number,a.group_code," +
                        "a.student_state,a.current_round,a.participation_status,a.participation_reason,a.content_version FROM t_student_experiment_assignment a " +
                        "JOIN t_user u ON u.id=a.user_id WHERE a.experiment_id=? ORDER BY a.student_id", experimentId)));
        files.put("assessments.csv", csv(jdbc.queryForList("SELECT ca.assignment_id,ca.assessment_code,cr.question_code,ca.status,ca.started_at,ca.deadline_at,ca.submitted_at," +
                        "ca.final_score,ca.max_score,cr.answer,cr.is_correct,cr.score,cr.duration_ms FROM t_content_assessment_attempt ca " +
                        "LEFT JOIN t_content_assessment_response cr ON cr.attempt_id=ca.id JOIN t_student_experiment_assignment a ON a.id=ca.assignment_id " +
                        "WHERE a.experiment_id=? ORDER BY ca.assignment_id,ca.assessment_code,cr.question_code", experimentId)));
        files.put("tutor_runs.csv", csv(jdbc.queryForList("SELECT r.id tutor_run_id,r.assignment_id,r.round_no,r.group_code_snapshot,r.mode,r.status,r.interaction_count," +
                        "r.learner_state_status,r.started_at,r.completed_at FROM t_experiment_tutor_run r JOIN t_student_experiment_assignment a ON a.id=r.assignment_id " +
                        "WHERE a.experiment_id=? ORDER BY r.assignment_id,r.round_no", experimentId)));
        files.put("tutor_messages.csv", csv(jdbc.queryForList("SELECT m.tutor_run_id,m.session_id,m.role,m.message_type,m.content,m.step,m.correctness," +
                        "m.knowledge_points::text knowledge_points,m.client_timestamp,m.server_timestamp FROM t_tutor_message m " +
                        "JOIN t_experiment_tutor_run r ON r.id=m.tutor_run_id JOIN t_student_experiment_assignment a ON a.id=r.assignment_id " +
                        "WHERE a.experiment_id=? ORDER BY m.server_timestamp,m.id", experimentId)));
        files.put("learner_states.csv", csv(jdbc.queryForList("SELECT r.assignment_id,r.round_no,r.learner_state_status,r.completed_at FROM t_experiment_tutor_run r " +
                        "JOIN t_student_experiment_assignment a ON a.id=r.assignment_id WHERE a.experiment_id=? ORDER BY r.assignment_id,r.round_no", experimentId)));
        files.put("events.jsonl", events(experimentId));
        files.put("manifest.json", manifest(experimentId));
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            jdbc.update("INSERT INTO t_experiment_export_audit(id,experiment_id,admin_user_id,export_format) VALUES(?,?,?,?)",
                    UUID.randomUUID().toString(), experimentId, adminId, "ZIP");
            return output.toByteArray();
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "实验导出失败", exception);
        }
    }

    private String events(String experimentId) {
        StringBuilder output = new StringBuilder();
        for (Map<String, Object> row : jdbc.queryForList("SELECT event_id,client_event_id,assignment_id,experiment_group,round_no,experiment_stage," +
                "tutor_mode,question_id,session_id,event_type,event_data::text event_data,client_timestamp,server_timestamp " +
                "FROM t_experiment_event_log WHERE experiment_id=? ORDER BY server_timestamp,event_id", experimentId)) {
            try { output.append(objectMapper.writeValueAsString(row)).append('\n'); }
            catch (Exception exception) { throw new BusinessException(ResultCode.INTERNAL_ERROR, "事件导出失败", exception); }
        }
        return output.toString();
    }

    private String manifest(String experimentId) {
        try { return objectMapper.writeValueAsString(Map.of("schemaVersion", 1, "experimentId", experimentId)); }
        catch (Exception exception) { throw new BusinessException(ResultCode.INTERNAL_ERROR, "导出清单生成失败", exception); }
    }

    private String csv(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "";
        List<String> columns = rows.get(0).keySet().stream().sorted().toList();
        StringBuilder output = new StringBuilder("\uFEFF").append(String.join(",", columns)).append("\r\n");
        for (Map<String, Object> row : rows) {
            for (int index = 0; index < columns.size(); index++) {
                if (index > 0) output.append(',');
                String value = String.valueOf(row.getOrDefault(columns.get(index), ""));
                output.append('"').append(value.replace("\"", "\"\"")).append('"');
            }
            output.append("\r\n");
        }
        return output.toString();
    }
}
