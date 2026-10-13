package com.intellitutor.scut.modules.experiment.service;

import cn.hutool.crypto.digest.BCrypt;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.modules.user.dto.vo.LoginVO;
import com.intellitutor.scut.modules.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LocalParticipantExperimentService {
    private final JdbcTemplate jdbc;
    private final UserService users;
    private final ExperimentEventService events;

    @Transactional
    public LoginVO start(StartReq request) {
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT a.user_id FROM t_local_experiment_participant p " +
                        "JOIN t_student_experiment_assignment a ON a.id=p.assignment_id " +
                        "WHERE p.participant_name=? AND p.phone_number=? ORDER BY a.created_at DESC LIMIT 1",
                request.name(), request.phoneNumber());
        if (!existing.isEmpty()) return users.issueLocalExperimentLogin(String.valueOf(existing.get(0).get("user_id")));
        return create(request.name(), request.phoneNumber());
    }

    @Transactional
    public LoginVO restart(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.id assignment_id,p.participant_name name,p.phone_number " +
                "FROM t_student_experiment_assignment a JOIN t_local_experiment_participant p ON p.assignment_id=a.id " +
                "WHERE a.user_id=? AND a.participation_status='ACTIVE' FOR UPDATE", userId);
        if (rows.isEmpty()) throw new BusinessException(ResultCode.EXPERIMENT_NOT_ASSIGNED);
        Map<String, Object> current = rows.get(0);
        jdbc.update("UPDATE t_student_experiment_assignment SET participation_status='ABANDONED',updated_at=NOW()," +
                "last_activity_at=NOW(),participation_reason='participant_restart' WHERE id=?", current.get("assignment_id"));
        events.recordSystem(userId, "EXPERIMENT_RESTARTED", Map.of("assignmentId", current.get("assignment_id")));
        return create(String.valueOf(current.get("name")), String.valueOf(current.get("phone_number")));
    }

    private LoginVO create(String name, String phoneNumber) {
        Map<String, Object> experiment = currentExperiment();
        String userId = UUID.randomUUID().toString();
        String assignmentId = UUID.randomUUID().toString();
        String localKey = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO t_user(id,email,login_account,password,nickname,roles,must_change_password,deleted) " +
                        "VALUES(?,?,?,?,?,'STUDENT',FALSE,0)", userId, "local-" + localKey + "@participant.local",
                "local-" + localKey, BCrypt.hashpw(UUID.randomUUID().toString(), BCrypt.gensalt()), name);
        jdbc.update("INSERT INTO t_student_experiment_assignment(id,experiment_id,user_id,student_id,student_state," +
                        "participation_status,content_version,registration_method) VALUES(?,?,?,?, 'WAITING_MODE_SELECTION','ACTIVE',?,'LOCAL')",
                assignmentId, experiment.get("id"), userId, userId, experiment.get("content_version"));
        jdbc.update("INSERT INTO t_local_experiment_participant(assignment_id,participant_name,phone_number) VALUES(?,?,?)",
                assignmentId, name, phoneNumber);
        events.recordSystem(userId, "EXPERIMENT_REGISTERED", Map.of("method", "LOCAL"));
        return users.issueLocalExperimentLogin(userId);
    }

    private Map<String, Object> currentExperiment() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,content_version FROM t_experiment " +
                "WHERE code='machine-learning' AND status='ACTIVE' AND content_version IS NOT NULL");
        if (rows.size() != 1) throw new BusinessException(ResultCode.NOT_FOUND, "本地机器学习实验尚未初始化");
        return rows.get(0);
    }

    public record StartReq(String name, String phoneNumber) {}
}
