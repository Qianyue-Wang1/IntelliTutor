package com.intellitutor.scut.modules.session.dto.req;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /v1/discussion/feedback 请求体。
 *
 * <p>
 * Python 模型字段（DiscussionFeedbackRequest）：session_id, feedback, submission_type,
 * behavior_data, active_step_id, userId。
 *
 * <p>
 * {@link #withSessionId(String)} 用于将 Java sessionId 替换为 Python sessionId。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class DiscussionFeedbackReq {

    /**
     * 会话 ID（Controller 层替换为 pythonSessionId 后再发给 MQ）。
     * JSON 键：session_id
     */
    private String sessionId;

    /** 用户反馈文本 */
    private String feedback;

    /**
     * 提交来源：option 表示点击快捷选项，free_text 表示输入框提问。
     * JSON 键：submission_type
     */
    private String submissionType = "free_text";

    /** 学生提交时处于活动状态的 DiscussionPlan 节点标识。 */
    private String activeStepId;

    /**
     * 行为数据（复杂嵌套，原样透传给 Python）。
     * JSON 键：behavior_data
     */
    private Object behaviorData;

    /**
     * 用户 ID（Python 字段名 userId，默认 "default"）。
     */
    @JsonProperty("userId")
    private String userId = "default";

    /**
     * 返回一个新的 {@code DiscussionFeedbackReq}，其 sessionId 已替换为指定值。
     */
    public DiscussionFeedbackReq withSessionId(String newSessionId) {
        return new DiscussionFeedbackReq(
                newSessionId,
                this.feedback,
                this.submissionType,
                this.activeStepId,
                this.behaviorData,
                this.userId
        );
    }
}
