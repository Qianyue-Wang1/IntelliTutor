package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParticipantExperimentExportServiceTest {

    @Test
    void recordsFallsBackToTheCreationTimeWhenAnAttemptHasNoStartedAt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-12T10:00:00+08:00");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "id", "assignment-1", "participant_name", "测试学生", "phone_number", "13900139999",
                "student_state", "WAITING_PRETEST", "created_at", createdAt)));

        var records = new ParticipantExperimentExportService(jdbc, new ObjectMapper()).records("current-user");

        assertEquals(createdAt, records.get(0).startedAt());
    }

    @Test
    void recordsConvertsJdbcTimestampsBeforeReturningTheCreationTimeFallback() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-12T10:00:00+08:00");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "id", "assignment-1", "participant_name", "测试学生", "phone_number", "13900139999",
                "student_state", "WAITING_PRETEST", "created_at", Timestamp.from(createdAt.toInstant()))));

        var records = new ParticipantExperimentExportService(jdbc, new ObjectMapper()).records("current-user");

        assertEquals(createdAt, records.get(0).startedAt());
    }

    @Test
    void exportWorkbookKeepsEachResearchDatasetAndExcludesPrivateContent() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> assignment = completedAssignment();
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForList(contains("package_json::text"), any(Object[].class))).thenReturn(List.of(assignment));

        Map<String, String> files = unzip(new ParticipantExperimentExportService(jdbc, new ObjectMapper())
                .exportWorkbook("current-user", "assignment-1"));

        assertTrue(files.get("xl/workbook.xml").contains("参与者信息"));
        assertTrue(files.get("xl/workbook.xml").contains("测试作答"));
        assertTrue(files.get("xl/workbook.xml").contains("行为事件"));
        assertFalse(String.join("", files.values()).contains("internal prompt"));
        assertFalse(String.join("", files.values()).contains("answerKey"));
    }

    @Test
    void exportWorkbookUsesCreationTimeWhenTheAssignmentStartTimeIsMissing() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> assignment = completedAssignment();
        assignment.put("started_at", null);
        assignment.put("created_at", OffsetDateTime.parse("2026-10-12T10:00:00+08:00"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForList(contains("package_json::text"), any(Object[].class))).thenReturn(List.of(assignment));

        Map<String, String> files = unzip(new ParticipantExperimentExportService(jdbc, new ObjectMapper())
                .exportWorkbook("current-user", "assignment-1"));

        assertTrue(String.join("", files.values()).contains("2026-10-12T10:00+08:00"));
    }

    @Test
    void exportWorkbookKeepsResearchHeadersWhenNoTutorRecordsExist() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForList(contains("package_json::text"), any(Object[].class))).thenReturn(List.of(completedAssignment()));

        byte[] workbook = new ParticipantExperimentExportService(jdbc, new ObjectMapper()).exportWorkbook("current-user", "assignment-1");

        try (XSSFWorkbook parsed = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
            assertEquals(List.of("experiment_stage", "round_no", "tutor_mode", "assessment_code", "assessment_status",
                            "attempt_started_at", "deadline_at", "attempt_submitted_at", "final_score", "max_score",
                            "question_code", "selected_answer", "revision", "first_viewed_at", "first_answered_at",
                            "last_answered_at", "change_count", "answer_changed", "response_submitted_at", "duration_ms",
                            "is_correct", "score"),
                    headerValues(parsed, "测试作答"));
            assertEquals(List.of("round_no", "learning_mode", "step_key", "submission_type", "submitted_answer", "correctness", "retry_count", "answered_at"),
                    headerValues(parsed, "子步骤作答"));
            assertEquals(List.of("round_no", "group_code_snapshot", "mode", "status", "interaction_count", "started_at", "completed_at",
                            "role", "message_type", "student_message", "correctness", "client_timestamp", "server_timestamp"),
                    headerValues(parsed, "学生交互"));
        }
    }

    @Test
    void excludesLegacyPretestAttemptsWhenExportingAV2Assignment() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> assignment = completedAssignment();
        assignment.put("content_version", "unsupervised-learning-v2");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForList(contains("package_json::text"), any(Object[].class))).thenReturn(List.of(assignment));

        new ParticipantExperimentExportService(jdbc, new ObjectMapper()).exportWorkbook("current-user", "assignment-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).queryForList(sql.capture(), any(Object[].class));
        assertTrue(sql.getAllValues().stream().anyMatch(value -> value.contains("ca.assessment_code <> 'PRETEST'")));
    }

    @Test
    void exportDerivesAssessmentStageFromTheStoredAssessmentCode() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForList(contains("package_json::text"), any(Object[].class))).thenReturn(List.of(completedAssignment()));

        new ParticipantExperimentExportService(jdbc, new ObjectMapper()).exportWorkbook("current-user", "assignment-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).queryForList(sql.capture(), any(Object[].class));
        String assessmentSql = sql.getAllValues().stream().filter(value -> value.contains("t_content_assessment_attempt ca"))
                .findFirst().orElseThrow();
        assertTrue(assessmentSql.contains("ca.assessment_code='POSTTEST'"));
        assertTrue(assessmentSql.contains("ROUND_' || substring(ca.assessment_code from 2) || '_QUIZ"));
        assertFalse(assessmentSql.contains("ca.stage_id"));
    }

    private Map<String, Object> completedAssignment() {
        Map<String, Object> assignment = new LinkedHashMap<>();
        assignment.put("assignment_id", "assignment-1");
        assignment.put("participant_name", "测试学生");
        assignment.put("phone_number", "13900139999");
        assignment.put("selected_mode", "BASIC_TUTOR");
        assignment.put("student_state", "EXPERIMENT_FINISHED");
        assignment.put("started_at", OffsetDateTime.parse("2026-10-12T10:00:00+08:00"));
        assignment.put("completed_at", OffsetDateTime.parse("2026-10-12T11:00:00+08:00"));
        assignment.put("content_version", "machine-learning-v1");
        assignment.put("package_json", "{\"directPrompts\":{\"M1\":\"internal prompt\"},\"assessments\":{\"pretest\":[{\"prompt\":\"题干\",\"answerKey\":\"A\"}]}}");
        return assignment;
    }

    private List<String> headerValues(XSSFWorkbook workbook, String sheetName) {
        var header = workbook.getSheet(sheetName).getRow(0);
        return IntStream.range(0, Math.max(0, header.getLastCellNum()))
                .mapToObj(index -> header.getCell(index).getStringCellValue()).toList();
    }

    private Map<String, String> unzip(byte[] archive) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                input.transferTo(output);
                files.put(entry.getName(), output.toString(StandardCharsets.UTF_8));
            }
        }
        return files;
    }
}
