package com.intellitutor.scut.modules.session.dto.req;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

/**
 * POST /v1/explanation/updatestate 请求体。
 *
 * <p>
 * Python 模型字段：session_id, imageCode, current_step, in_summary, wrong_answers。
 * 注意 Python 的 {@code imageCode} 是 camelCase，需要 {@code @JsonProperty} 覆盖。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class UpdateExplanationStateReq {

    /** 会话 ID（Controller 层替换为 pythonSessionId 后再发给 Python） */
    private String sessionId;

    /**
     * 图片代码列表（Python 字段名为 imageCode，保持 camelCase）。
     */
    @JsonProperty("imageCode")
    private List<String> imageCode;

    /** 当前步骤编号 */
    private Integer currentStep;

    /** 是否处于总结模式 */
    private Boolean inSummary;

    /** 错题列表 */
    private List<WrongAnswer> wrongAnswers;
}
