package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExperimentCredentialServiceTest {
    @Test
    void rejectsCredentialBeforeThePosttestIsComplete() {
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql.contains("t_experiment_credential")) return List.of();
                return List.of(Map.of("id", "assignment-1", "student_state", "WAITING_POSTTEST"));
            }
        };
        ExperimentCredentialService service = new ExperimentCredentialService(jdbc, new ObjectMapper(), null);

        assertThrows(RuntimeException.class, () -> service.issue("student-1", "issue-1"));
    }

    @Test
    void returnsTheAlreadyIssuedCredentialWithoutInsertingAnotherOne() {
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                return List.of(Map.of("id", "credential-1", "credential_no", "EXP-OLD", "participant_name", "王小明",
                        "participant_code", "S001", "topic", "无监督学习", "selected_mode", "FULL_TUTOR"));
            }

            @Override public int update(String sql, Object... args) {
                throw new AssertionError("existing credential must not be inserted again");
            }
        };
        ExperimentCredentialService service = new ExperimentCredentialService(jdbc, new ObjectMapper(), null);

        assertEquals("EXP-OLD", service.issue("student-1", "issue-2").credentialNo());
    }

    @Test
    void rendersTheOwnedStoredCredentialAsPng() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                return List.of(Map.of("id", "credential-1", "credential_no", "EXP-OLD", "participant_name", "王小明",
                        "participant_code", "S001", "topic", "无监督学习", "selected_mode", "FULL_TUTOR"));
            }
        };
        ExperimentCredentialService service = new ExperimentCredentialService(jdbc, new ObjectMapper(), null);

        byte[] png = service.png("student-1", "credential-1");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));

        assertEquals((byte) 0x89, png[0]);
        assertNotNull(image);
        assertEquals(1280, image.getWidth());
        assertEquals(720, image.getHeight());
    }

    @Test
    void returnsTheCredentialCreatedByTheRequestThatWonTheAssignmentLock() {
        JdbcTemplate jdbc = new JdbcTemplate() {
            private int credentialLookups;

            @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql.contains("t_experiment_credential")) {
                    credentialLookups++;
                    return credentialLookups == 1 ? List.of() : List.of(Map.of("id", "credential-1", "credential_no", "EXP-WINNER",
                            "participant_name", "王小明", "participant_code", "S001", "topic", "无监督学习", "selected_mode", "FULL_TUTOR"));
                }
                return List.of(Map.of("id", "assignment-1", "student_state", "WAITING_CREDENTIAL"));
            }

            @Override public int update(String sql, Object... args) {
                throw new AssertionError("the losing request must not create another credential");
            }
        };
        ExperimentCredentialService service = new ExperimentCredentialService(jdbc, new ObjectMapper(), null);

        assertEquals("EXP-WINNER", service.issue("student-1", "issue-loser").credentialNo());
    }
}
