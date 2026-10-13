package com.intellitutor.scut.modules.experiment.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentRegistrationServiceTest {

    @Test
    void exposesOnlyTheAdministratorEnabledRegistrationMethods() {
        RegistrationJdbcTemplate jdbc = new RegistrationJdbcTemplate();
        ExperimentRegistrationService service = new ExperimentRegistrationService(jdbc, null, null);

        assertEquals(List.of("SMS_VERIFIED"), service.registrationOptions("experiment-a").methods());
        jdbc.policy = "SMS_OR_INVITE";
        assertEquals(List.of("SMS_VERIFIED", "INVITE_CODE"), service.registrationOptions("experiment-a").methods());
        jdbc.policy = "INVITE_CODE";
        assertEquals(List.of("INVITE_CODE"), service.registrationOptions("experiment-a").methods());
    }

    @Test
    void exposesTheOnlyOpenExperimentWithoutRequiringStudentsToKnowItsCode() {
        RegistrationJdbcTemplate jdbc = new RegistrationJdbcTemplate();
        ExperimentRegistrationService service = new ExperimentRegistrationService(jdbc, null, null);

        assertEquals(List.of("SMS_VERIFIED"), service.currentRegistrationOptions().methods());
    }

    @Test
    void rejectsAnUnknownRegistrationPolicyInsteadOfGuessingAFallback() {
        RegistrationJdbcTemplate jdbc = new RegistrationJdbcTemplate();
        jdbc.policy = "UNKNOWN";
        ExperimentRegistrationService service = new ExperimentRegistrationService(jdbc, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.registrationOptions("experiment-a"));
    }

    @Test
    void smsRegistrationCreatesAPretestReadyAssignmentWithLockedContent() {
        RegistrationJdbcTemplate jdbc = new RegistrationJdbcTemplate();
        ExperimentRegistrationService service = new ExperimentRegistrationService(jdbc,
                new com.intellitutor.scut.modules.user.service.SmsVerifyCodeService() {
                    @Override public void sendVerifyCode(String phoneNumber) { }
                    @Override public boolean checkVerifyCode(String phoneNumber, String code) { return true; }
                }, null);

        ExperimentRegistrationService.RegistrationResult result = service.register(
                new ExperimentRegistrationService.ExperimentRegisterReq("experiment-a", "张三", "13800138000",
                        "safe-password", "SMS_VERIFIED", "123456"));

        assertEquals("13800138000", result.loginAccount());
        assertFalse(result.userId().isBlank());
        assertTrue(jdbc.updates.stream().anyMatch(sql -> sql.contains("INSERT INTO t_user") && sql.contains("login_account")));
        assertTrue(jdbc.updates.stream().anyMatch(sql -> sql.contains("t_student_experiment_assignment")
                && sql.contains("WAITING_PRETEST") && sql.contains("content_version")));
    }

    @Test
    void registrationIsTransactionalSoUserAndAssignmentCannotDiverge() throws NoSuchMethodException {
        assertNotNull(ExperimentRegistrationService.class
                .getMethod("register", ExperimentRegistrationService.ExperimentRegisterReq.class)
                .getAnnotation(Transactional.class));
    }

    @Test
    void sendsSmsOnlyWhenTheExperimentAllowsSmsRegistration() {
        RegistrationJdbcTemplate jdbc = new RegistrationJdbcTemplate();
        RecordingSmsVerifyCodeService sms = new RecordingSmsVerifyCodeService();
        ExperimentRegistrationService service = new ExperimentRegistrationService(jdbc, sms, null);

        service.sendRegistrationSms("experiment-a", "13800138000");

        assertEquals("13800138000", sms.sentPhone);
        jdbc.policy = "INVITE_CODE";
        assertThrows(RuntimeException.class, () -> service.sendRegistrationSms("experiment-a", "13900139000"));
        assertEquals("13800138000", sms.sentPhone);
    }

    @Test
    void inviteRegistrationConsumesTheCodeAndCreatesTheSamePretestReadyAssignment() {
        RegistrationJdbcTemplate jdbc = new RegistrationJdbcTemplate();
        jdbc.policy = "INVITE_CODE";
        jdbc.inviteHash = cn.hutool.crypto.digest.BCrypt.hashpw("invite-123", cn.hutool.crypto.digest.BCrypt.gensalt());
        ExperimentRegistrationService service = new ExperimentRegistrationService(jdbc, null, null);

        ExperimentRegistrationService.RegistrationResult result = service.register(
                new ExperimentRegistrationService.ExperimentRegisterReq("experiment-a", "李四", "13900139000",
                        "safe-password", "INVITE_CODE", "invite-123"));

        assertEquals("13900139000", result.loginAccount());
        assertTrue(jdbc.updates.stream().anyMatch(sql -> sql.contains("INSERT INTO t_user")));
        assertTrue(jdbc.updates.stream().anyMatch(sql -> sql.contains("t_student_experiment_assignment")
                && sql.contains("WAITING_PRETEST")));
        assertTrue(jdbc.updates.stream().anyMatch(sql -> sql.contains("t_experiment_invite_code")
                && sql.contains("used_count=used_count+1")));
    }

    private static final class RegistrationJdbcTemplate extends JdbcTemplate {
        private String policy = "SMS_VERIFIED";
        private String inviteHash;
        private final java.util.ArrayList<String> updates = new java.util.ArrayList<>();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("t_experiment_invite_code")) {
                if (inviteHash == null) return List.of();
                return List.of(Map.of("id", "invite-1", "code_hash", inviteHash, "max_uses", 2, "used_count", 0));
            }
            return List.of(Map.of("id", "experiment-1", "registration_policy", policy,
                    "content_version", "unsupervised-learning-v1"));
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql) {
            return List.of(Map.of("id", "experiment-1", "code", "machine-learning",
                    "registration_policy", policy, "content_version", "unsupervised-learning-v1"));
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (sql.contains("COUNT(*)")) return requiredType.cast(0);
            throw new AssertionError("未预期查询: " + sql);
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql);
            return 1;
        }
    }

    private static final class RecordingSmsVerifyCodeService implements com.intellitutor.scut.modules.user.service.SmsVerifyCodeService {
        private String sentPhone;

        @Override public void sendVerifyCode(String phoneNumber) { sentPhone = phoneNumber; }
        @Override public boolean checkVerifyCode(String phoneNumber, String code) { return true; }
    }
}
