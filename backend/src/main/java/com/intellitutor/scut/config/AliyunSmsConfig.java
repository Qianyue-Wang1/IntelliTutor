package com.intellitutor.scut.config;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.dypnsapi20170525.AsyncClient;
import darabonba.core.client.ClientOverrideConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云号码认证服务 AsyncClient 配置。
 *
 * <p>
 * 仅在 {@code intellitutor.aliyun.dypns.enabled=true} 时激活。
 * SDK 凭证通过 {@code application.yml} 中的 {@code access-key-id} /
 * {@code access-key-secret} 显式注入，不依赖本地凭证文件。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AliyunSmsProperties.class)
@ConditionalOnProperty(prefix = "intellitutor.aliyun.dypns", name = "enabled", havingValue = "true")
public class AliyunSmsConfig {

    /**
     * 创建阿里云 Dypnsapi 异步客户端 Bean。
     *
     * <p>
     * 使用 {@link StaticCredentialProvider} 从配置属性中读取 AK/SK，
     * 避免依赖本地 {@code ~/.aliyun/credentials} 文件，便于容器化部署和切换云厂商。
     * Spring 容器销毁时自动调用 {@code close()} 释放资源。
     *
     * @param properties 阿里云 SMS 配置属性
     * @return AsyncClient 单例
     */
    @Bean(destroyMethod = "close")
    public AsyncClient aliyunDypnsAsyncClient(AliyunSmsProperties properties) {
        log.info("[AliyunSms] 初始化 AsyncClient: region={}, endpoint={}", properties.getRegion(),
                properties.getEndpoint());

        StaticCredentialProvider provider = StaticCredentialProvider.create(
                Credential.builder()
                        .accessKeyId(properties.getAccessKeyId())
                        .accessKeySecret(properties.getAccessKeySecret())
                        .build());

        return AsyncClient.builder()
                .region(properties.getRegion())
                .credentialsProvider(provider)
                .overrideConfiguration(
                        ClientOverrideConfiguration.create()
                                .setEndpointOverride(properties.getEndpoint()))
                .build();
    }
}
