package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExperimentExportServiceTest {

    @Test
    void producesTheAuditableResearchArchiveLayout() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ExperimentAdminService admin = mock(ExperimentAdminService.class);
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object>any())).thenReturn(List.of());
        ExperimentExportService service = new ExperimentExportService(jdbc, new ObjectMapper(), admin);

        byte[] archive = service.exportZip("admin-1", "experiment-1");
        List<String> names;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            names = new java.util.ArrayList<>();
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) names.add(entry.getName());
        }

        assertEquals(List.of("participants.csv", "assessments.csv", "tutor_runs.csv", "tutor_messages.csv",
                "learner_states.csv", "events.jsonl", "manifest.json"), names);
        assertTrue(archive.length > 0);
        verify(jdbc).update(contains("t_experiment_export_audit"), org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(), org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any());
    }
}
