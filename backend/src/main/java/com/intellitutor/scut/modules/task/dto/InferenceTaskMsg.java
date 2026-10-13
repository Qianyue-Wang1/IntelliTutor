package com.intellitutor.scut.modules.task.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * RabbitMQ 推理任务消息体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InferenceTaskMsg implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Java 任务 UUID，用于轮询和结果回填 */
    private String taskId;

    /** Java 会话 UUID */
    private String sessionId;

    /** EXPLANATION_INIT | EXPLANATION_GETSTEP | EXPLANATION_COMMIT_ANSWER | DISCUSSION_INIT | DISCUSSION_FEEDBACK */
    private String taskType;

    /** Python 接口路径，路径变量已解析 */
    private String pythonEndpoint;

    /** GET | POST */
    private String httpMethod;

    /** 序列化后的 JSON 请求体，GET 任务为 null */
    private String requestBodyJson;

    /** 操作用户 ID */
    private String userId;
}
