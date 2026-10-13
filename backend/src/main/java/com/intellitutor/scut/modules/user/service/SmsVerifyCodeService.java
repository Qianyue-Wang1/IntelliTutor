package com.intellitutor.scut.modules.user.service;

/**
 * 短信验证码服务抽象接口（策略模式）。
 *
 * <p>
 * 定义短信验证码的发送与校验行为，具体实现可切换不同短信服务商。
 * 当前默认实现为阿里云号码认证服务（Dypnsapi），未来可替换为腾讯云、Twilio 等。
 *
 * <p>
 * <b>扩展方式：</b>新增一个实现类并标记 {@code @Primary} 或通过
 * {@code @ConditionalOnProperty} 控制激活策略即可无缝切换。
 *
 * @author intellitutor-team
 * @since 1.0.0
 * @see com.intellitutor.scut.modules.user.service.impl.AliyunSmsVerifyCodeServiceImpl
 */
public interface SmsVerifyCodeService {

    /**
     * 向指定手机号发送短信验证码。
     *
     * <p>
     * 验证码由服务商侧生成并发送，平台不感知明文。
     *
     * @param phoneNumber 目标手机号（中国大陆 11 位）
     * @throws com.intellitutor.scut.common.exception.BusinessException
     *                                                                  发送失败时抛出
     *                                                                  {@code VERIFY_CODE_SEND_FAILED}
     */
    void sendVerifyCode(String phoneNumber);

    /**
     * 校验短信验证码是否正确。
     *
     * @param phoneNumber 目标手机号
     * @param code        用户输入的验证码
     * @return {@code true} 验证通过，{@code false} 验证码错误或已过期
     */
    boolean checkVerifyCode(String phoneNumber, String code);
}
