package com.intellitutor.scut.modules.session.dto.req;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

/**
 * POST /v1/discussion/updatestate 请求体。
 *
 * <p>
 * Python 模型字段（DiscussionUpdateStateRequest）：
 * session_id, question, images, imageCode, current_step, discussion_tree,
 * messages_history, knowledge_points, is_complete, summary_text。
 *
 * <p>
 * 嵌套的 discussion_tree（含 StepData 树形结构）、messages_history、knowledge_points
 * 使用 {@code Object} / {@code List<Object>} 类型，Jackson 在反序列化时保留原始 Map 结构，
 * 重新序列化后原样透传给 Python，避免嵌套命名策略不一致问题。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class DiscussionUpdateStateReq {

    /** 会话 ID（Controller 层替换为 pythonSessionId 后再发给 Python） */
    private String sessionId;

    /** 原始题目文本 */
    private String question;

    /** 附带图片列表（可选） */
    private List<ImageData> images;

    /**
     * 图片代码列表（Python 字段名 imageCode，保持 camelCase）。
     */
    @JsonProperty("imageCode")
    private List<String> imageCode;

    /** 当前步骤 ID（字符串形式） */
    private String currentStep;

    /**
     * 讨论树（StepData 嵌套结构），使用 Object 类型原样透传。
     * JSON 键：discussion_tree
     */
    private List<Object> discussionTree;

    /**
     * 消息历史列表，使用 Object 类型原样透传。
     * JSON 键：messages_history
     */
    private List<Object> messagesHistory;

    /**
     * 知识点列表，使用 Object 类型原样透传。
     * JSON 键：knowledge_points
     */
    private List<Object> knowledgePoints;

    /** 是否已完成讨论 */
    private Boolean isComplete;

    /** 总结文本（可选） */
    private String summaryText;
}
