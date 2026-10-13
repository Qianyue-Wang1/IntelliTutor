package com.intellitutor.scut.modules.session.dto.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 异步任务受理响应 VO（HTTP 202）。
 *
 * <p>
 * 前端收到 202 后，通过 {@code taskId} 轮询 {@code GET /v1/task/{taskId}} 获取最终结果。
 * Jackson 全局 SNAKE_CASE 策略自动序列化为 {@code task_id} / {@code session_id}，
 * 与前端期望的命名一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskAcceptedVO {

    /** MQ 任务唯一标识，前端用于轮询结果 */
    private String taskId;

    /** Java 会话 ID */
    private String sessionId;
}
