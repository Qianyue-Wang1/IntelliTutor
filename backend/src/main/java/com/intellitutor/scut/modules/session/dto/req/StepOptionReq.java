package com.intellitutor.scut.modules.session.dto.req;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * POST /v1/explanation/commitanswer 请求体。
 *
 * <p>
 * Python 字段名（snake_case）：session_id, option, behavior_data, active_step_id。
 * 使用 {@code @JsonNaming(SnakeCaseStrategy)} 覆盖全局 SNAKE_CASE 后双向一致。
 *
 * <p>
 * {@link #withSessionId(String)} 用于在 Controller 层将 Java sessionId
 * 替换为 Python sessionId 后生成新实例。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class StepOptionReq {

    /**
     * 会话 ID（Java 侧接收 Java sessionId；发送至 MQ 前替换为 pythonSessionId）。
     * JSON 键：session_id
     */
    private String sessionId;

    /** 用户选择的选项字母，如 "A" / "B" / "C" */
    private String option;

    /** 学生提交时正在作答的教学步骤标识。 */
    private String activeStepId;

    /** 行为数据（可选，原样透传给 Python），Python 字段 behavior_data */
    private Map<String, Object> behaviorData;

    /**
     * 返回一个新的 {@code StepOptionReq}，其 sessionId 已替换为指定值。
     * 用于将 Java sessionId 替换为 Python sessionId 后放入 MQ 消息体。
     */
    public StepOptionReq withSessionId(String newSessionId) {
        return new StepOptionReq(newSessionId, this.option, this.activeStepId, this.behaviorData);
    }
}
