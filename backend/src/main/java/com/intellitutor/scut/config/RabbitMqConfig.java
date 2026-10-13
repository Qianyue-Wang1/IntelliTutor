package com.intellitutor.scut.config;

import com.intellitutor.scut.common.constant.MqConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 拓扑结构与基础设施配置
 *
 * <p>
 * <b>整体拓扑如下：</b>
 * 
 * <pre>
 *  [SessionService / TaskService]
 *         │  publish(routingKey)
 *         ▼
 *  ┌─────────────────────────────────────┐
 *  │  intellitutor.llm.inference.exchange│  (Topic Exchange)
 *  └──────────────┬──────────────────────┘
 *                 │ llm.inference.discuss / llm.inference.explain / llm.inference.kt
 *                 ▼
 *  ┌──────────────────────────────────┐
 *  │  intellitutor.llm.inference.queue│  (Durable Queue, TTL=30min)
 *  └──────────────────────────────────┘
 *         │  x-dead-letter-exchange → intellitutor.dead.letter.exchange
 *         │  (重试 3 次仍失败后路由到死信队列)
 *         ▼
 *  ┌──────────────────────────────┐
 *  │  intellitutor.dead.letter.queue│
 *  └──────────────────────────────┘
 * </pre>
 *
 * <p>
 * <b>关键设计决策：</b>
 * <ul>
 * <li>消息序列化使用 {@link Jackson2JsonMessageConverter}，明确携带 {@code __TypeId__} 头，
 * TypeId 策略映射到 DTO 类，保证跨服务可读性。</li>
 * <li>Publisher Confirm + Return Callback 在 {@link RabbitTemplate} 上注册，
 * 保证生产者侧消息可靠投递（对应 {@code application.yml} 中
 * publisher-confirm-type=correlated}）。</li>
 * <li>主队列设置 TTL=120s，超时后消息进入死信队列，不阻塞主流程。</li>
 * </ul>
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Slf4j
@Configuration
public class RabbitMqConfig {

    /** 允许长任务造成的排队等待，避免后续消息在消费者空闲前过期。 */
    private static final int INFERENCE_MESSAGE_TTL_MS = 30 * 60 * 1000;

    // ================================================================
    // 1. 消息序列化
    // ================================================================

    /**
     * 全局消息转换器：将 Java 对象序列化为 JSON 传输，并在消息头中写入类型信息。
     *
     * <p>
     * 接收方（{@code InferenceListener}）无需手动反序列化，
     * Spring AMQP 会根据 {@code __TypeId__} 头自动映射到对应 DTO 类。
     *
     * @return Jackson2JsonMessageConverter 实例
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 自定义 {@link RabbitTemplate}，注册 Publisher Confirm 和 Return Callback，
     * 实现生产者侧消息可靠投递监控。
     *
     * <p>
     * <b>Publisher Confirm</b>：消息到达 Exchange 后 Broker 回调，
     * {@code ack=false} 说明 Exchange 不存在或网络异常，需告警。
     *
     * <p>
     * <b>Return Callback</b>：消息无法路由到任何 Queue 时回调（如 routingKey 拼写错误），
     * 需记录日志并触发告警，防止静默丢消息。
     *
     * @param connectionFactory Spring 自动注入的连接工厂
     * @return 配置好回调的 RabbitTemplate
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());

        // Publisher Confirm Callback
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                String msgId = correlationData != null ? correlationData.getId() : "unknown";
                log.error("[MQ-Confirm] 消息投递至 Exchange 失败！msgId={}, cause={}", msgId, cause);
                // TODO: 后续接入告警（钉钉/邮件）或写入补偿表
            }
        });

        // Return Callback（消息无法路由到 Queue 时触发）
        template.setReturnsCallback(returned -> log.error(
                "[MQ-Return] 消息无法路由至队列！exchange={}, routingKey={}, replyText={}, body={}",
                returned.getExchange(),
                returned.getRoutingKey(),
                returned.getReplyText(),
                new String(returned.getMessage().getBody())));

        return template;
    }

    // ================================================================
    // 2. Exchange 声明
    // ================================================================

    /**
     * 主推理 Topic Exchange。
     *
     * <p>
     * {@code durable=true}：Broker 重启后 Exchange 配置不丢失。
     *
     * @return TopicExchange Bean
     */
    @Bean
    public TopicExchange llmInferenceExchange() {
        return ExchangeBuilder
                .topicExchange(MqConstants.EXCHANGE_LLM_INFERENCE)
                .durable(true)
                .build();
    }

    /**
     * 死信 Exchange（Direct 类型，路由规则简单固定）。
     *
     * @return DirectExchange Bean
     */
    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder
                .directExchange(MqConstants.EXCHANGE_DEAD_LETTER)
                .durable(true)
                .build();
    }

    // ================================================================
    // 3. Queue 声明
    // ================================================================

    /**
     * 主推理队列（持久化 + 死信转发配置）。
     *
     * <p>
     * 参数说明：
     * <ul>
     * <li>{@code x-message-ttl=1800000}：消息可排队 30 分钟，覆盖多个长任务串行等待。</li>
     * <li>{@code x-dead-letter-exchange}：死信 Exchange 名称。</li>
     * <li>{@code x-dead-letter-routing-key}：死信消息路由键，路由至死信队列。</li>
     * <li>{@code x-max-length=10000}：队列消息上限 1 万条，超出时头部消息转死信（背压保护）。</li>
     * </ul>
     *
     * @return Queue Bean
     */
    @Bean
    public Queue llmInferenceQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", INFERENCE_MESSAGE_TTL_MS); // 消息排队 TTL 30min
        args.put("x-dead-letter-exchange", MqConstants.EXCHANGE_DEAD_LETTER); // 死信 Exchange
        args.put("x-dead-letter-routing-key", "dead.letter.inference"); // 死信 Routing Key
        args.put("x-max-length", 10_000); // 队列容量上限

        return QueueBuilder
                .durable(MqConstants.QUEUE_LLM_INFERENCE)
                .withArguments(args)
                .build();
    }

    /**
     * 死信队列（持久化，不设 TTL，供人工/定时任务核查）。
     *
     * @return Queue Bean
     */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder
                .durable(MqConstants.QUEUE_DEAD_LETTER)
                .build();
    }

    // ================================================================
    // 4. Binding 绑定
    // ================================================================

    /**
     * 将主推理队列绑定到推理 Exchange，使用通配符 {@code llm.inference.#}。
     *
     * <p>
     * 所有以 {@code llm.inference.} 开头的路由键（discuss / explain / kt 等）
     * 均路由至此队列，{@code InferenceListener} 按消息内 {@code taskType} 字段做二次分发。
     *
     * @return Binding Bean
     */
    @Bean
    public Binding inferenceQueueBinding() {
        return BindingBuilder
                .bind(llmInferenceQueue())
                .to(llmInferenceExchange())
                .with("llm.inference.#");
    }

    /**
     * 将死信队列绑定到死信 Exchange。
     *
     * @return Binding Bean
     */
    @Bean
    public Binding deadLetterQueueBinding() {
        return BindingBuilder
                .bind(deadLetterQueue())
                .to(deadLetterExchange())
                .with("dead.letter.inference");
    }
}
