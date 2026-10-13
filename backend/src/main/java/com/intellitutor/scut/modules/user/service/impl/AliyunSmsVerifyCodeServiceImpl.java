package com.intellitutor.scut.modules.user.service.impl;

import com.aliyun.sdk.service.dypnsapi20170525.AsyncClient;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.intellitutor.scut.common.constant.MqConstants;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.config.AliyunSmsProperties;
import com.intellitutor.scut.modules.user.service.SmsVerifyCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 阿里云号码认证服务（Dypnsapi）短信验证码实现。
 *
 * <p>使用 V2 异步 SDK 发送短信，但验证码改由服务端本地生成并缓存到 Redis，
 * 校验时仅做本地比对，避免依赖云端 {@code checkSmsVerifyCode}。
 *
 * <p><b>可替换性设计：</b>本类实现 {@link SmsVerifyCodeService} 接口，切换其他短信服务商时
 * 只需新增实现类并通过 {@code @Primary} 或配置属性切换激活即可。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(AsyncClient.class)
public class AliyunSmsVerifyCodeServiceImpl implements SmsVerifyCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AsyncClient asyncClient;
    private final AliyunSmsProperties smsProperties;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * {@inheritDoc}
     *
     * <p>服务端本地生成验证码，缓存到 Redis 后通过阿里云模板短信发送。
     * 若发送失败则回滚 Redis 中的验证码缓存。
     */
    @Override
    public void sendVerifyCode(String phoneNumber) {
        log.info("[AliyunSms] 发送短信验证码: phone={}", maskPhone(phoneNumber));

        String code = generateCode();
        String redisKey = buildRedisKey(phoneNumber);
        String templateParam = buildTemplateParam(code);

        stringRedisTemplate.opsForValue().set(redisKey, code,
                smsProperties.getCodeTtlMinutes().longValue(), TimeUnit.MINUTES);

        SendSmsVerifyCodeRequest request = SendSmsVerifyCodeRequest.builder()
                .phoneNumber(phoneNumber)
                .signName(smsProperties.getSignName())
                .templateCode(smsProperties.getTemplateCode())
                .templateParam(templateParam)
                .codeLength(smsProperties.getCodeLength().longValue())
                .validTime(smsProperties.getCodeTtlMinutes().longValue())
                .build();

        try {
            CompletableFuture<SendSmsVerifyCodeResponse> future = asyncClient.sendSmsVerifyCode(request);
            SendSmsVerifyCodeResponse response = future.get();

            String respCode = response.getBody().getCode();
            if (!"OK".equalsIgnoreCase(respCode)) {
                log.error("[AliyunSms] 发送失败: code={}, message={}", respCode, response.getBody().getMessage());
                stringRedisTemplate.delete(redisKey);
                throw new BusinessException(ResultCode.VERIFY_CODE_SEND_FAILED,
                        "短信验证码发送失败: " + response.getBody().getMessage());
            }
            log.info("[AliyunSms] 短信验证码发送成功: phone={}", maskPhone(phoneNumber));

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            stringRedisTemplate.delete(redisKey);
            log.error("[AliyunSms] 发送异常: phone={}", maskPhone(phoneNumber), e);
            throw new BusinessException(ResultCode.SMS_SERVICE_UNAVAILABLE, "短信服务调用异常: " + e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>校验 Redis 中缓存的短信验证码，验证通过后立即删除，保证一次性使用。
     */
    @Override
    public boolean checkVerifyCode(String phoneNumber, String code) {
        log.info("[AliyunSms] 校验短信验证码: phone={}", maskPhone(phoneNumber));

        String redisKey = buildRedisKey(phoneNumber);
        String cachedCode = stringRedisTemplate.opsForValue().get(redisKey);
        if (cachedCode != null && cachedCode.equals(code)) {
            stringRedisTemplate.delete(redisKey);
            return true;
        }
        return false;
    }

    /**
     * 手机号脱敏（保留前 3 后 4）。
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String generateCode() {
        int length = smsProperties.getCodeLength();
        int bound = (int) Math.pow(10, length);
        int code = RANDOM.nextInt(bound);
        return String.format("%0" + length + "d", code);
    }

    private String buildRedisKey(String phoneNumber) {
        return MqConstants.VERIFY_CODE_PREFIX + "sms:" + phoneNumber;
    }

    private String buildTemplateParam(String code) {
        return String.format("{\"%s\":\"%s\",\"%s\":\"%s\"}",
                smsProperties.getTemplateCodeParamKey(),
                code,
                smsProperties.getTemplateMinutesParamKey(),
                smsProperties.getCodeTtlMinutes());
    }
}
