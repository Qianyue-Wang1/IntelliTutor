package com.intellitutor.scut.modules.session.dto.req;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * POST /v1/explanation/init 请求体。
 *
 * <p>
 * 命名策略说明：
 * <ul>
 * <li>大部分字段遵循 snake_case（与前端传入一致，Spring MVC 的 SNAKE_CASE 全局策略自动处理）。</li>
 * <li>{@code processedQuestion}、{@code userInput}、{@code personalizedMode}、
 * {@code imageCode}、{@code userId} 保持 camelCase，通过 {@code @JsonProperty} 显式指定，
 * 确保前端→Java 和 Java→Python 双向序列化正确。</li>
 * </ul>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ExplanationInitReq {

    /**
     * 经前端预处理后的题目文本（Python 字段名为 processedQuestion）。
     * 前端与 Python 均使用 camelCase，需覆盖全局 SNAKE_CASE 策略。
     */
    @NotBlank(message = "题目内容不能为空")
    @Size(max = 20_000, message = "Question content is too long")
    @Pattern(regexp = "^(?![\\s\\S]*<\\s*/?\\s*[A-Za-z][^>]*>)[\\s\\S]*$",
            message = "Question content must not contain HTML tags")
    @JsonProperty("processedQuestion")
    private String processedQuestion;

    /** 学生可见的学习主题；省略时 Python 回退显示 processedQuestion。 */
    @JsonProperty("displayQuestion")
    private String displayQuestion;

    /** 用户手写解题思路（可选，Python 字段名 userInput） */
    @JsonProperty("userInput")
    private String userInput;

    /** 是否开启个性化模式（Python 字段名 personalizedMode） */
    @JsonProperty("personalizedMode")
    private Boolean personalizedMode;

    /** Current-session adaptation; enabled only for the Full Tutor study arm. */
    @JsonProperty("adaptiveMode")
    private Boolean adaptiveMode;

    /** Read historical learner state only for eligible Full Tutor rounds. */
    @JsonProperty("crossSessionEnabled")
    private Boolean crossSessionEnabled;

    /** Persist learner state after Full Tutor runs. */
    @JsonProperty("persistLearnerState")
    private Boolean persistLearnerState;

    @JsonProperty("experimentRunId")
    private String experimentRunId;

    @JsonProperty("learnerStateScope")
    private String learnerStateScope;

    @JsonProperty("topic")
    private String topic;
    @JsonProperty("objectives")
    private List<String> objectives;
    @JsonProperty("pretestSummary")
    private Map<String, Boolean> pretestSummary;
    @JsonProperty("priorRoundState")
    private List<Map<String, Object>> priorRoundState;
    @JsonProperty("interactionLimit")
    private Integer interactionLimit;
    @JsonProperty("forceCoverageOnFinalTurn")
    private Boolean forceCoverageOnFinalTurn;

    /** 附带图片列表（可选） */
    private List<ImageData> images;

    /**
     * 图片代码列表（Python 字段名 imageCode），用于 GeoGebra 等图形代码传递。
     */
    @JsonProperty("imageCode")
    private List<String> imageCode;

    /**
     * 用户 ID（Python 字段名 userId，默认 "default"）。
     */
    @JsonProperty("userId")
    private String userId = "default";
}
