package com.intellitutor.scut.modules.experiment.service;

import cn.hutool.crypto.digest.BCrypt;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.modules.user.service.SmsVerifyCodeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ExperimentRegistrationService {
    private final JdbcTemplate jdbc;
    private final SmsVerifyCodeService smsVerifyCodes;
    private final ExperimentEventService events;

    public ExperimentRegistrationService(JdbcTemplate jdbc, SmsVerifyCodeService smsVerifyCodes, ExperimentEventService events) {
        this.jdbc = jdbc;
        this.smsVerifyCodes = smsVerifyCodes;
        this.events = events;
    }

    public RegistrationOptions registrationOptions(String experimentCode) {
        return new RegistrationOptions(methods(experiment(experimentCode)));
    }

    public RegistrationOptions currentRegistrationOptions() {
        return new RegistrationOptions(methods(currentExperiment()));
    }

    public void sendCurrentRegistrationSms(String phoneNumber) {
        sendRegistrationSms(String.valueOf(currentExperiment().get("code")), phoneNumber);
    }

    public RegistrationResult registerCurrent(CurrentExperimentRegisterReq request) {
        return register(new ExperimentRegisterReq(String.valueOf(currentExperiment().get("code")), request.name(),
                request.phoneNumber(), request.password(), request.method(), request.proof()));
    }

    public void sendRegistrationSms(String experimentCode, String phoneNumber) {
        Map<String, Object> experiment = experiment(experimentCode);
        if (!methods(experiment).contains("SMS_VERIFIED")) {
            throw new BusinessException(ResultCode.FORBIDDEN, "当前实验未开放短信报名");
        }
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM t_user WHERE phone_number=? AND deleted=0", Integer.class,
                phoneNumber);
        if (existing != null && existing > 0) throw new BusinessException(ResultCode.PHONE_ALREADY_BOUND);
        smsVerifyCodes.sendVerifyCode(phoneNumber);
    }

    @Transactional(rollbackFor = Exception.class)
    public RegistrationResult register(ExperimentRegisterReq request) {
        Map<String, Object> experiment = experiment(request.experimentCode());
        if (!methods(experiment).contains(request.method())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "当前实验未开放该报名方式");
        }
        if ("SMS_VERIFIED".equals(request.method())) {
            if (!smsVerifyCodes.checkVerifyCode(request.phoneNumber(), request.proof())) {
                throw new BusinessException(ResultCode.VERIFY_CODE_INVALID);
            }
        } else if (!"INVITE_CODE".equals(request.method())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "未知报名方式");
        }
        requirePhoneAvailable(request.phoneNumber());
        if ("INVITE_CODE".equals(request.method())) consumeInvite(String.valueOf(experiment.get("id")), request.proof());
        String contentVersion = String.valueOf(experiment.get("content_version"));
        if (contentVersion.isBlank() || "null".equals(contentVersion)) {
            throw new IllegalArgumentException("实验尚未发布内容包");
        }

        String userId = UUID.randomUUID().toString();
        String assignmentId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO t_user(id,phone_number,login_account,password,nickname,roles,must_change_password,deleted) " +
                        "VALUES(?,?,?,?,?,'STUDENT',FALSE,0)", userId, request.phoneNumber(), request.phoneNumber(),
                BCrypt.hashpw(request.password(), BCrypt.gensalt()), request.name());
        jdbc.update("INSERT INTO t_student_experiment_assignment(id,experiment_id,user_id,student_id,student_state,participation_status,content_version,registration_method) " +
                        "VALUES(?,?,?,?, 'WAITING_PRETEST','ACTIVE',?,?)", assignmentId, experiment.get("id"), userId,
                userId, contentVersion, request.method());
        if (events != null) events.recordSystem(userId, "EXPERIMENT_REGISTERED", Map.of("method", request.method()));
        return new RegistrationResult(userId, request.phoneNumber());
    }

    private Map<String, Object> experiment(String experimentCode) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,registration_policy,content_version FROM t_experiment " +
                "WHERE code=? AND status='ACTIVE'", experimentCode);
        if (rows.isEmpty()) throw new IllegalArgumentException("实验不存在或未开放报名");
        return rows.get(0);
    }

    private Map<String, Object> currentExperiment() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,code,registration_policy,content_version FROM t_experiment " +
                "WHERE status='ACTIVE' AND content_version IS NOT NULL ORDER BY started_at NULLS LAST, created_at DESC LIMIT 2");
        if (rows.size() != 1) throw new IllegalStateException("当前必须恰好开放一个实验");
        return rows.get(0);
    }

    private void requirePhoneAvailable(String phoneNumber) {
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM t_user WHERE phone_number=? AND deleted=0", Integer.class,
                phoneNumber);
        if (existing != null && existing > 0) throw new BusinessException(ResultCode.PHONE_ALREADY_BOUND);
    }

    private void consumeInvite(String experimentId, String proof) {
        List<Map<String, Object>> invites = jdbc.queryForList("SELECT id,code_hash,max_uses,used_count FROM t_experiment_invite_code " +
                "WHERE experiment_id=? AND enabled=TRUE AND (expires_at IS NULL OR expires_at > NOW()) FOR UPDATE", experimentId);
        for (Map<String, Object> invite : invites) {
            if (!BCrypt.checkpw(proof, String.valueOf(invite.get("code_hash")))) continue;
            Number maxUses = (Number) invite.get("max_uses");
            Number usedCount = (Number) invite.get("used_count");
            if (maxUses != null && usedCount.longValue() >= maxUses.longValue()) {
                throw new BusinessException(ResultCode.FORBIDDEN, "邀请码已达使用上限");
            }
            int updated = jdbc.update("UPDATE t_experiment_invite_code SET used_count=used_count+1 WHERE id=? AND used_count=?",
                    invite.get("id"), usedCount.longValue());
            if (updated != 1) throw new BusinessException(ResultCode.FORBIDDEN, "邀请码已被使用");
            return;
        }
        throw new BusinessException(ResultCode.FORBIDDEN, "邀请码无效、已停用或已过期");
    }

    private List<String> methods(Map<String, Object> experiment) {
        String policy = String.valueOf(experiment.get("registration_policy"));
        return switch (policy) {
            case "SMS_VERIFIED" -> List.of("SMS_VERIFIED");
            case "SMS_OR_INVITE", "BOTH" -> List.of("SMS_VERIFIED", "INVITE_CODE");
            case "INVITE_CODE" -> List.of("INVITE_CODE");
            default -> throw new IllegalArgumentException("未知报名策略: " + policy);
        };
    }

    public record RegistrationOptions(List<String> methods) {}
    public record ExperimentRegisterReq(String experimentCode, String name, String phoneNumber, String password,
                                        String method, String proof) {}
    public record CurrentExperimentRegisterReq(String name, String phoneNumber, String password, String method,
                                               String proof) {}
    public record RegistrationResult(String userId, String loginAccount) {}
}
