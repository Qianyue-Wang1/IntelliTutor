package com.intellitutor.scut.modules.task.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异步推理任务记录实体，对应 PostgreSQL {@code t_task} 表。
 *
 * <p>
 * 任务类型（{@code taskType}）枚举值：
 * <ul>
 * <li>{@code EXPLANATION_INIT} — 讲解模式初始化（LLM 生成步骤树，30~60s）</li>
 * <li>{@code EXPLANATION_GETSTEP} — 获取当前步骤选项（LLM 生成，3-8s）</li>
 * <li>{@code EXPLANATION_COMMIT_ANSWER} — 提交步骤答案（含错误分析 LLM，3-8s）</li>
 * <li>{@code DISCUSSION_INIT} — 讨论模式初始化（LLM 生成讨论树，30~60s）</li>
 * <li>{@code DISCUSSION_FEEDBACK} — 提交讨论反馈（LLM 评估回复，3-8s）</li>
 * </ul>
 *
 * <p>
 * 状态流转：{@code queued} → {@code processing} → {@code success} / {@code failed}
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
@TableName("t_task")
public class TaskRecord {

    /** Java 生成的 UUID 主键，前端轮询时使用 */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 关联 t_session.id */
    private String sessionId;

    /**
     * 任务类型，见类注释中的枚举值说明。
     * 
     * @see #taskType
     */
    private String taskType;

    /** 任务状态：queued | processing | success | failed */
    private String status;

    /**
     * 发送给 Python 的请求体 JSON 字符串（GET 类型任务为 null）。
     * 快照作用：调试、重放。
     */
    private String requestBody;

    /** Python 返回的完整响应 JSON 字符串（成功后填入） */
    private String result;

    /** 失败原因（MQ 消费者失败时写入） */
    private String errorMessage;

    /** 记录创建时间，由 MyBatis-Plus MetaHandler 自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** MQ 消费完成时间（success/failed 均填入） */
    private LocalDateTime completedAt;
}
