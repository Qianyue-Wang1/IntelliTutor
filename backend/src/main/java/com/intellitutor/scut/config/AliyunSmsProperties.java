package com.intellitutor.scut.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云号码认证服务（Dypnsapi）配置属性。
 *
 * <p>
 * 映射 {@code application.yml} 中 {@code intellitutor.aliyun.dypns.*} 配置项。
 * 访问密钥通过环境变量 {@code ALIBABA_CLOUD_ACCESS_KEY_ID} /
 * {@code ALIBABA_CLOUD_ACCESS_KEY_SECRET} 注入到 SDK 默认凭证链。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
@ConfigurationProperties(prefix = "intellitutor.aliyun.dypns")
public class AliyunSmsProperties {

    /** 是否启用阿里云短信服务 */
    private boolean enabled = true;

    /** API Endpoint */
    private String endpoint = "dypnsapi.aliyuncs.com";

    /** 区域 ID */
    private String region = "cn-hangzhou";

    /** AccessKey ID，从 application.yml 注入 */
    private String accessKeyId;

    /** AccessKey Secret，从 application.yml 注入 */
    private String accessKeySecret;

    /** 短信签名 */
    private String signName;

    /** 短信模板编码 */
    private String templateCode;

    /** 模板中验证码变量名*/
    private String templateCodeParamKey = "code";

    /** 模板中有效期变量名*/
    private String templateMinutesParamKey = "min";

    /** 验证码长度（4~8 位），从 application.yml 注入 */
    private Integer codeLength;

    /** 验证码有效期（分钟），从 application.yml 注入 */
    private Integer codeTtlMinutes;
}
