package com.intellitutor.scut.modules.experiment.service;

import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.config.FeatureProperties;
import com.intellitutor.scut.modules.experiment.domain.GlobalStage;
import com.intellitutor.scut.modules.experiment.domain.StudentState;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExperimentAccessService {
    private final JdbcTemplate jdbc;
    private final FeatureProperties features;

    public boolean hasAssignment(String userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM t_student_experiment_assignment WHERE user_id=?", Integer.class, userId);
        return count != null && count > 0;
    }

    public void rejectLegacyTutorForExperimentStudent(String userId) {
        if (features.isExperimentModeEnabled() && hasAssignment(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "实验账号不能创建自由 Tutor 会话");
        }
    }

    public void requireSessionAccess(String userId, String sessionId, String expectedType) {
        if (!features.isExperimentModeEnabled() || !hasAssignment(userId)) return;
        String sql = "SELECT COUNT(*) FROM t_experiment_tutor_run r " +
                        "JOIN t_student_experiment_assignment a ON a.id=r.assignment_id " +
                        "JOIN t_session s ON s.id=r.session_id " +
                        "WHERE a.user_id=? AND r.session_id=? AND a.participation_status='ACTIVE' " +
                        "AND r.status='IN_PROGRESS' AND a.student_state=(CASE r.round_no " +
                        "WHEN 1 THEN 'ROUND_1_TUTOR_IN_PROGRESS' WHEN 2 THEN 'ROUND_2_TUTOR_IN_PROGRESS' " +
                        "WHEN 3 THEN 'ROUND_3_TUTOR_IN_PROGRESS' WHEN 4 THEN 'ROUND_4_TUTOR_IN_PROGRESS' " +
                        "WHEN 5 THEN 'ROUND_5_TUTOR_IN_PROGRESS' END)";
        Integer count = expectedType == null
                ? jdbc.queryForObject(sql, Integer.class, userId, sessionId)
                : jdbc.queryForObject(sql + " AND s.session_type=?", Integer.class,
                        userId, sessionId, expectedType);
        if (count == null || count == 0) {
            throw new BusinessException(ResultCode.EXPERIMENT_STAGE_LOCKED, "该 Tutor 会话不属于当前实验阶段");
        }
    }

    public void requireStage(String userId, GlobalStage required) {
        var rows = jdbc.queryForList("SELECT a.student_state,a.participation_status " +
                "FROM t_student_experiment_assignment a WHERE a.user_id=?", userId);
        if (rows.isEmpty()) throw new BusinessException(ResultCode.EXPERIMENT_NOT_ASSIGNED);
        var row = rows.get(0);
        if (!"ACTIVE".equals(row.get("participation_status"))) throw new BusinessException(ResultCode.FORBIDDEN, "账号已退出实验");
        StudentState state = StudentState.valueOf(String.valueOf(row.get("student_state")));
        if (!state.canEnter(required)) {
            throw new BusinessException(ResultCode.EXPERIMENT_STAGE_LOCKED, "当前个人状态为 " + state);
        }
    }
}
