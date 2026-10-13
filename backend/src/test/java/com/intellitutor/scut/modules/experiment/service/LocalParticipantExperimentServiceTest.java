package com.intellitutor.scut.modules.experiment.service;

import com.intellitutor.scut.modules.user.dto.vo.LoginVO;
import com.intellitutor.scut.modules.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalParticipantExperimentServiceTest {

    @Test
    void startsAnIndependentLocalAttemptWithoutPasswordOrInvite() {
        LocalJdbc jdbc = new LocalJdbc();
        UserService users = mock(UserService.class);
        LoginVO login = LoginVO.builder().accessToken("access").refreshToken("refresh").build();
        when(users.issueLocalExperimentLogin(anyString())).thenReturn(login);
        LocalParticipantExperimentService service = new LocalParticipantExperimentService(jdbc, users,
                mock(ExperimentEventService.class));

        LoginVO result = service.start(new LocalParticipantExperimentService.StartReq("张三", "13800138000"));

        assertSame(login, result);
        assertTrue(jdbc.updates.stream().anyMatch(sql -> sql.contains("INSERT INTO t_user") && sql.contains("login_account")));
        assertTrue(jdbc.updates.stream().anyMatch(sql -> sql.contains("t_student_experiment_assignment") && sql.contains("'LOCAL'")));
        assertTrue(jdbc.updates.stream().anyMatch(sql -> sql.contains("'WAITING_MODE_SELECTION'")));
        assertTrue(jdbc.updates.stream().anyMatch(sql -> sql.contains("t_local_experiment_participant")));
    }

    @Test
    void restoresTheExistingLocalParticipantByNameAndPhoneWithoutCreatingAnotherAttempt() {
        LocalJdbc jdbc = new LocalJdbc();
        jdbc.existingUserId = "existing-user";
        UserService users = mock(UserService.class);
        LoginVO login = LoginVO.builder().accessToken("access").refreshToken("refresh").build();
        when(users.issueLocalExperimentLogin("existing-user")).thenReturn(login);
        LocalParticipantExperimentService service = new LocalParticipantExperimentService(jdbc, users,
                mock(ExperimentEventService.class));

        LoginVO result = service.start(new LocalParticipantExperimentService.StartReq("张三", "13800138000"));

        assertSame(login, result);
        verify(users).issueLocalExperimentLogin("existing-user");
        assertTrue(jdbc.updates.isEmpty());
    }

    @Test
    void restartAbandonsTheCurrentLocalAttemptBeforeCreatingANewOne() {
        LocalJdbc jdbc = new LocalJdbc();
        UserService users = mock(UserService.class);
        LoginVO login = LoginVO.builder().accessToken("new-access").refreshToken("new-refresh").build();
        when(users.issueLocalExperimentLogin(anyString())).thenReturn(login);
        LocalParticipantExperimentService service = new LocalParticipantExperimentService(jdbc, users,
                mock(ExperimentEventService.class));

        assertSame(login, service.restart("old-user"));
        assertTrue(jdbc.updates.stream().anyMatch(sql -> sql.contains("participation_status='ABANDONED'")));
        assertTrue(jdbc.updates.stream().anyMatch(sql -> sql.contains("t_local_experiment_participant")));
    }

    private static final class LocalJdbc extends JdbcTemplate {
        private final List<String> updates = new ArrayList<>();
        private String existingUserId;

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("SELECT a.user_id")) {
                return existingUserId == null ? List.of() : List.of(Map.of("user_id", existingUserId));
            }
            if (sql.contains("t_local_experiment_participant")) {
                return List.of(Map.of("assignment_id", "old-assignment", "name", "张三", "phone_number", "13800138000"));
            }
            return List.of(Map.of("id", "experiment-1", "content_version", "unsupervised-learning-v1"));
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql) {
            return List.of(Map.of("id", "experiment-1", "content_version", "unsupervised-learning-v1"));
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql);
            return 1;
        }
    }
}
