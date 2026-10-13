package com.intellitutor.scut.modules.user.service;

import com.intellitutor.scut.common.constant.MqConstants;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/**
 * 邮箱验证码服务。
 *
 * <p>
 * 负责邮箱验证码的生成、Redis 缓存和邮件发送。
 * 验证码使用 {@link SecureRandom} 生成，存储在 Redis 并设置过期时间。
 *
 * <p>
 * Redis Key 格式：{@code intellitutor:verify:{scene}:{email}}
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerifyCodeService {

    private final JavaMailSender mailSender;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${intellitutor.mail.from}")
    private String mailFrom;

    @Value("${intellitutor.mail.subject-prefix}")
    private String subjectPrefix;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CODE_LENGTH = 6;

    /**
     * 生成并发送邮箱验证码。
     *
     * @param email 目标邮箱地址
     * @param scene 业务场景标识（如
     *              {@code "register_email"}、{@code "login_email"}、{@code "change_email"}）
     * @throws BusinessException 邮件发送失败时抛出
     */
    public void sendVerifyCode(String email, String scene) {
        String code = generateCode();
        String redisKey = MqConstants.VERIFY_CODE_PREFIX + scene + ":" + email;

        // 写入 Redis，TTL 5 分钟
        stringRedisTemplate.opsForValue().set(redisKey, code,
                MqConstants.VERIFY_CODE_TTL_SECONDS, TimeUnit.SECONDS);

        // 组装并发送邮件
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(email);
            message.setSubject(subjectPrefix + " 验证码");
            message.setText(buildEmailContent(code, scene));
            mailSender.send(message);
            log.info("[EmailVerify] 验证码已发送: email={}, scene={}", maskEmail(email), scene);
        } catch (Exception e) {
            log.error("[EmailVerify] 邮件发送失败: email={}, scene={}", maskEmail(email), scene, e);
            // 清除已写入的 Redis 缓存
            stringRedisTemplate.delete(redisKey);
            throw new BusinessException(ResultCode.VERIFY_CODE_SEND_FAILED, "邮件发送失败，请稍后重试");
        }
    }

    /**
     * 校验邮箱验证码。
     *
     * <p>
     * 验证通过后立即删除 Redis 中的验证码，保证一次性使用。
     *
     * @param email 目标邮箱
     * @param code  用户输入的验证码
     * @param scene 业务场景标识
     * @return {@code true} 验证通过
     */
    public boolean checkVerifyCode(String email, String code, String scene) {
        String redisKey = MqConstants.VERIFY_CODE_PREFIX + scene + ":" + email;
        String cachedCode = stringRedisTemplate.opsForValue().get(redisKey);

        if (cachedCode != null && cachedCode.equals(code)) {
            // 验证成功后删除，一次性使用
            stringRedisTemplate.delete(redisKey);
            return true;
        }
        return false;
    }

    /**
     * 生成 N 位纯数字验证码。
     */
    private String generateCode() {
        int bound = (int) Math.pow(10, CODE_LENGTH);
        int code = RANDOM.nextInt(bound);
        return String.format("%0" + CODE_LENGTH + "d", code);
    }

    /**
     * 构建邮件正文。
     */
    private String buildEmailContent(String code, String scene) {
        String action = switch (scene) {
            case "register_email" -> "注册账号";
            case "login_email" -> "登录验证";
            case "change_email" -> "修改邮箱";
            case "change_password_email" -> "修改密码";
            default -> "操作验证";
        };

        return String.format("""
                您好！

                您正在进行【%s】操作，验证码为：

                    %s

                验证码 %d 分钟内有效，请勿泄露给他人。

                如非本人操作，请忽略此邮件。

                —— IntelliTutor 智能伴学平台
                """, action, code, MqConstants.VERIFY_CODE_TTL_SECONDS / 60);
    }

    /**
     * 邮箱脱敏。
     */
    private String maskEmail(String email) {
        if (email == null) {
            return "***";
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 2) {
            return "***" + email.substring(atIndex);
        }
        return email.substring(0, 2) + "***" + email.substring(atIndex);
    }
}
