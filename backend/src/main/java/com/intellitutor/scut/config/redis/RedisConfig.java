package com.intellitutor.scut.config.redis;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 序列化与 Template 配置
 *
 * <p>
 * 默认的 Spring Data Redis {@link RedisTemplate} 使用 JDK 序列化，存入 Redis 后 Key/Value
 * 均为二进制乱码，不便于运维排查。本配置将其替换为：
 * <ul>
 * <li><b>Key</b>：{@link StringRedisSerializer}（UTF-8 字符串，可在 redis-cli
 * 直接可读）</li>
 * <li><b>Value /
 * HashValue</b>：{@link Jackson2JsonRedisSerializer}（JSON，携带类型信息可自动反序列化）</li>
 * <li><b>HashKey</b>：{@link StringRedisSerializer}（同 Key 策略）</li>
 * </ul>
 *
 * <p>
 * 同时注册支持 {@code java.time.*} 类型（如 {@link java.time.LocalDateTime}）的
 * {@link JavaTimeModule}，避免序列化报错。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Configuration
public class RedisConfig {

    /**
     * 通用 {@link RedisTemplate}，Key 为 String，Value 为 JSON 序列化的任意 Java 对象。
     *
     * <p>
     * 用于存储会话状态（{@code SessionState}）、知识追踪中间结果等结构化数据。
     *
     * @param connectionFactory Redis 连接工厂（由 Spring Boot 自动装配）
     * @return 配置好序列化策略的 RedisTemplate
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {

        // 构建 ObjectMapper：开启所有可见性 + 写入类型信息，支持运行时多态反序列化
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        // 支持 Java 8+ 时间类型
        objectMapper.registerModule(new JavaTimeModule());

        Jackson2JsonRedisSerializer<Object> jsonSerializer = new Jackson2JsonRedisSerializer<>(objectMapper,
                Object.class);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key 使用字符串序列化，Value 使用 JSON
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);

        // Hash 结构：Field 也使用字符串，Value 使用 JSON
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 纯字符串 {@link StringRedisTemplate}，用于 Token 黑名单、防重放锁等简单 KV 场景。
     *
     * <p>
     * Spring Boot 自动装配已提供默认实例，此处显式声明是为了与主 {@link RedisTemplate} 并存时
     * 避免 Bean 名称冲突，并统一使用相同的 {@link RedisConnectionFactory}。
     *
     * @param connectionFactory Redis 连接工厂
     * @return StringRedisTemplate 实例
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
