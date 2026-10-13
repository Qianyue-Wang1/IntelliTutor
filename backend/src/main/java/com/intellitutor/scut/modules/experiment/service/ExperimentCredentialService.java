package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ExperimentCredentialService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ExperimentEventService events;

    public ExperimentCredentialService(JdbcTemplate jdbc, ObjectMapper objectMapper, ExperimentEventService events) {
        this.jdbc = jdbc; this.objectMapper = objectMapper; this.events = events;
    }

    @Transactional
    public ExperimentDtos.CredentialVO issue(String userId, String idempotencyKey) {
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT c.* FROM t_experiment_credential c JOIN t_student_experiment_assignment a ON a.id=c.assignment_id WHERE a.user_id=?", userId);
        if (!existing.isEmpty()) return vo(existing.get(0));
        List<Map<String, Object>> assignments = jdbc.queryForList("SELECT a.id,a.student_id,a.selected_mode,a.student_state,a.experiment_id,u.nickname," +
                "e.name experiment_name FROM t_student_experiment_assignment a JOIN t_user u ON u.id=a.user_id JOIN t_experiment e ON e.id=a.experiment_id WHERE a.user_id=? FOR UPDATE OF a", userId);
        if (assignments.isEmpty()) throw new BusinessException(ResultCode.EXPERIMENT_NOT_ASSIGNED);
        List<Map<String, Object>> issuedWhileWaiting = jdbc.queryForList("SELECT c.* FROM t_experiment_credential c JOIN t_student_experiment_assignment a ON a.id=c.assignment_id WHERE a.user_id=?", userId);
        if (!issuedWhileWaiting.isEmpty()) return vo(issuedWhileWaiting.get(0));
        Map<String, Object> assignment = assignments.get(0);
        if (!"WAITING_CREDENTIAL".equals(String.valueOf(assignment.get("student_state"))))
            throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "完成后测后才能领取凭证");
        String id = UUID.randomUUID().toString();
        String number = "EXP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        jdbc.update("INSERT INTO t_experiment_credential(id,assignment_id,credential_no,participant_name,participant_code,topic,selected_mode,issue_idempotency_key) VALUES(?,?,?,?,?,?,?,?)",
                id, assignment.get("id"), number, String.valueOf(assignment.get("nickname")), String.valueOf(assignment.get("student_id")),
                String.valueOf(assignment.get("experiment_name")), String.valueOf(assignment.get("selected_mode")), idempotencyKey);
        jdbc.update("UPDATE t_student_experiment_assignment SET student_state='EXPERIMENT_FINISHED',completed_at=NOW(),updated_at=NOW(),version=version+1 WHERE id=? AND student_state='WAITING_CREDENTIAL'", assignment.get("id"));
        if (events != null) events.recordSystem(userId, "CREDENTIAL_ISSUED", Map.of("credentialId", id));
        return new ExperimentDtos.CredentialVO(id, number, String.valueOf(assignment.get("nickname")), String.valueOf(assignment.get("student_id")),
                String.valueOf(assignment.get("experiment_name")), String.valueOf(assignment.get("selected_mode")), OffsetDateTime.now(ZoneOffset.UTC));
    }

    public byte[] png(String userId, String credentialId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT c.* FROM t_experiment_credential c JOIN t_student_experiment_assignment a ON a.id=c.assignment_id WHERE c.id=? AND a.user_id=?", credentialId, userId);
        if (rows.isEmpty()) throw new BusinessException(ResultCode.EXPERIMENT_STATE_CONFLICT, "完成凭证不存在");
        ExperimentDtos.CredentialVO credential = vo(rows.get(0));
        BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(249, 250, 244));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.WHITE);
            graphics.fillRoundRect(190, 90, 900, 540, 28, 28);
            graphics.setColor(new Color(89, 104, 47));
            graphics.setFont(new Font("Dialog", Font.BOLD, 20));
            graphics.drawString("实验已完成", 245, 150);
            graphics.setColor(new Color(45, 54, 33));
            graphics.setFont(new Font("Dialog", Font.BOLD, 38));
            graphics.drawString("你已完成本次测试", 245, 210);
            graphics.setFont(new Font("Dialog", Font.PLAIN, 21));
            graphics.setColor(new Color(78, 89, 68));
            graphics.drawString("完成记录已保存，感谢你参加本次实验。", 245, 260);
            int y = 330;
            String[] fields = {"参与者：" + credential.participantName(), "知识主题：" + credential.topic(),
                    "实验模式（研究组）：" + credential.selectedMode(), "参与编号：" + credential.participantCode(),
                    "凭证编号：" + credential.credentialNo(), "签发时间：" + credential.issuedAt()};
            for (String field : fields) {
                graphics.drawString(field, 245, y);
                y += 48;
            }
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成完成凭证", exception);
        }
    }

    private ExperimentDtos.CredentialVO vo(Map<String, Object> row) {
        return new ExperimentDtos.CredentialVO(String.valueOf(row.get("id")), String.valueOf(row.get("credential_no")),
                String.valueOf(row.get("participant_name")), String.valueOf(row.get("participant_code")), String.valueOf(row.get("topic")),
                String.valueOf(row.get("selected_mode")), row.get("issued_at") instanceof OffsetDateTime value ? value : OffsetDateTime.now(ZoneOffset.UTC));
    }
}
