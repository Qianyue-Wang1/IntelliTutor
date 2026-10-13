package com.intellitutor.scut.common.constant;

/**
 * RabbitMQ 拓扑常量
 *
 * <p>
 * 集中管理 Exchange、Queue、Routing Key 的命名，避免硬编码散落各处。
 * 对应的 Bean 声明位于 {@code com.intellitutor.scut.config.RabbitMqConfig}。
 *
 * <p>
 * <b>命名规范（Topic Exchange 路由键）：</b>
 * {@code <domain>.<action>.<detail>}，如 {@code llm.inference.request}。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
public final class MqConstants {

    private MqConstants() {
        // 工具类，禁止实例化
    }

    // ===== Exchange =====

    /**
     * 主推理 Topic Exchange：接收所有 LLM 相关任务。
     * 使用 Topic 类型允许未来通过通配符路由键扩展新子任务类型。
     */
    public static final String EXCHANGE_LLM_INFERENCE = "intellitutor.llm.inference.exchange";

    /**
     * 死信 Exchange：存放消费失败并超过重试次数的消息，用于人工核查。
     */
    public static final String EXCHANGE_DEAD_LETTER = "intellitutor.dead.letter.exchange";

    // ===== Queue =====

    /**
     * 主推理队列：由 {@code InferenceListener} 消费，含讨论/讲解/KT 任务。
     */
    public static final String QUEUE_LLM_INFERENCE = "intellitutor.llm.inference.queue";

    /**
     * 死信队列：推理任务消费失败后的兜底队列，独立运营不影响主流程。
     */
    public static final String QUEUE_DEAD_LETTER = "intellitutor.dead.letter.queue";

    // ===== Routing Key =====

    /**
     * 讨论模式任务路由键：由 SessionService 投递，消费后转发至 Python {@code /api/v1/discuss}。
     */
    public static final String ROUTING_KEY_DISCUSS = "llm.inference.discuss";

    /**
     * 讲解模式任务路由键：由 SessionService 投递，消费后转发至 Python {@code /api/v1/explain}。
     */
    public static final String ROUTING_KEY_EXPLAIN = "llm.inference.explain";

    /**
     * 知识追踪任务路由键：由 TaskService 在推理完成后投递，消费后转发至 Python {@code /api/v1/kt}。
     */
    public static final String ROUTING_KEY_KT = "llm.inference.kt";

    /**
     * 死信路由键：所有死信消息统一使用此 key 路由至死信队列。
     */
    public static final String ROUTING_KEY_DEAD_LETTER = "dead.letter.#";

    // ===== Redis Key 前缀 =====

    /** 会话状态 Key 前缀，完整 Key = {@code SESSION_STATE_PREFIX + sessionId} */
    public static final String SESSION_STATE_PREFIX = "intellitutor:session:state:";

    /** 推理防重放锁 Key 前缀，完整 Key = {@code INFERENCE_LOCK_PREFIX + sessionId} */
    public static final String INFERENCE_LOCK_PREFIX = "intellitutor:inference:lock:";

    /** 用户 Token 黑名单前缀（登出时写入），完整 Key = {@code TOKEN_BLACKLIST_PREFIX + tokenId} */
    public static final String TOKEN_BLACKLIST_PREFIX = "intellitutor:token:blacklist:";

    /**
     * 任务状态 Key 前缀，完整 Key = {@code TASK_STATUS_PREFIX + taskId}。
     * 值：queued / processing / success / failed。TTL=2h。
     */
    public static final String TASK_STATUS_PREFIX = "intellitutor:task:status:";

    /**
     * 任务结果 Key 前缀，完整 Key = {@code TASK_RESULT_PREFIX + taskId}。
     * 值：Python 完整响应 JSON 字符串。TTL=1h（成功后写入）。
     */
    public static final String TASK_RESULT_PREFIX = "intellitutor:task:result:";

    /**
     * 会话元数据 Key 前缀，完整 Key = {@code SESSION_META_PREFIX + sessionId}。
     * 值：SessionRecord JSON 字符串，代理时用于快速读取 pythonSessionId，减少 DB 访问。TTL=4h。
     */
    public static final String SESSION_META_PREFIX = "intellitutor:session:meta:";

    /**
     * Refresh Token 前缀，完整 Key = {@code REFRESH_TOKEN_PREFIX + refreshToken} →
     * userId
     */
    public static final String REFRESH_TOKEN_PREFIX = "intellitutor:refresh:";

    /**
     * 验证码前缀，完整 Key = {@code VERIFY_CODE_PREFIX + scene + ":" + target}
     * <p>
     * 例：{@code intellitutor:verify:register_email:user@example.com}
     * </p>
     */
    public static final String VERIFY_CODE_PREFIX = "intellitutor:verify:";

    /** Refresh Token 默认有效期（秒）：7 天 */
    public static final long REFRESH_TOKEN_TTL_SECONDS = 7 * 24 * 3600L;

    /** 验证码默认有效期（秒）：5 分钟 */
    public static final long VERIFY_CODE_TTL_SECONDS = 5 * 60L;
}
