package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.modules.session.service.PythonProxyService;
import com.intellitutor.scut.modules.session.service.SessionService;
import com.intellitutor.scut.modules.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ExperimentTutorServiceStructuredLimitTest {
    @Test
    void rejectsTheThirtyFirstBasicOrFullInteraction() {
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                return List.of(Map.of("group_code_snapshot", "BASIC_TUTOR", "interaction_count", 30));
            }
        };
        ExperimentTutorService service = new ExperimentTutorService(jdbc, mock(SessionService.class), mock(TaskService.class),
                mock(ExperimentOrchestrationService.class), mock(ExperimentAccessService.class), mock(ExperimentEventService.class),
                new ObjectMapper(), mock(PythonProxyService.class));

        assertThrows(RuntimeException.class, () -> service.requireStructuredInteraction("student-1", "session-1"));
    }
}
