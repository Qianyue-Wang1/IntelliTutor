package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import com.intellitutor.scut.modules.session.service.PythonProxyService;
import com.intellitutor.scut.modules.session.service.SessionService;
import com.intellitutor.scut.modules.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExperimentTutorServiceDirectLimitTest {

    @Test
    void rejectsAFourthFollowupWhenTheDirectTutorAlreadyHasThreeReservedInteractions() {
        JdbcTemplate jdbc = new DirectJdbcTemplate();
        ExperimentAccessService access = mock(ExperimentAccessService.class);
        ExperimentTutorService service = new ExperimentTutorService(jdbc, mock(SessionService.class), mock(TaskService.class),
                mock(ExperimentOrchestrationService.class), access, mock(ExperimentEventService.class), new ObjectMapper(),
                mock(PythonProxyService.class));

        assertThrows(RuntimeException.class, () -> service.directFollowup("student-1", "session-1",
                new ExperimentDtos.DirectFollowupReq("请再解释一次", "followup-4", OffsetDateTime.now())));
    }

    private static final class DirectJdbcTemplate extends JdbcTemplate {
        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) {
            if (sql.contains("t_experiment_tutor_run")) return Map.of(
                    "id", "run-1", "group_code_snapshot", "DIRECT_LLM", "interaction_count", 3);
            return Map.of("python_session_id", "python-session-1");
        }

        @Override
        public int update(String sql, Object... args) {
            return 1;
        }
    }
}
