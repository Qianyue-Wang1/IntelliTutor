package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.config.ExperimentProperties;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import com.intellitutor.scut.modules.session.service.PythonProxyService;
import com.intellitutor.scut.modules.session.service.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ExperimentAdminServiceContentLockTest {

    @Test
    void studentImportLocksTheExperimentContentVersionBeforeThePretest() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        ExperimentAdminService service = new ExperimentAdminService(jdbc, new ObjectMapper(), mock(ExperimentEventService.class),
                new ExperimentProperties(), mock(SessionService.class), mock(PythonProxyService.class));
        ExperimentDtos.StudentImportReq request = new ExperimentDtos.StudentImportReq("experiment-1", List.of(
                new ExperimentDtos.StudentImportRow("S001", "student-1", "class-a")), false);

        ExperimentDtos.StudentImportResult result = service.importStudents("admin-1", request);

        assertFalse(result.dryRun());
        assertTrue(jdbc.updates.stream().anyMatch(sql -> sql.contains("t_student_experiment_assignment")
                && sql.contains("content_version") && sql.contains("SELECT") && sql.contains("t_experiment")));
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> updates = new ArrayList<>();

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (sql.contains("SELECT roles")) return requiredType.cast("ADMIN");
            if (sql.contains("COUNT(*)")) return requiredType.cast(0);
            throw new AssertionError("未预期查询: " + sql);
        }

        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) {
            return Map.of("status", "DRAFT", "current_stage_code", "SETUP");
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql);
            return 1;
        }
    }
}
