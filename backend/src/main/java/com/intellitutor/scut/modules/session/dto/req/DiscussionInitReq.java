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
 * POST /v1/discussion/init 请求体。
 *
 * <p>
 * Python 模型字段（DiscussionInitRequest）：
 * processedQuestion, images, imageCode, personalizedMode, userId。
 * camelCase 字段使用 {@code @JsonProperty} 显式覆盖。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class DiscussionInitReq {

    /**
     * 经前端预处理后的题目文本（Python 字段名 processedQuestion）。
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

    /** 附带图片列表（可选） */
    private List<ImageData> images;

    /**
     * 图片代码列表（Python 字段名 imageCode）。
     */
    @JsonProperty("imageCode")
    private List<String> imageCode;

    /**
     * 是否开启个性化模式（Python 字段名 personalizedMode）。
     */
    @JsonProperty("personalizedMode")
    private Boolean personalizedMode;

    @JsonProperty("adaptiveMode")
    private Boolean adaptiveMode;

    @JsonProperty("crossSessionEnabled")
    private Boolean crossSessionEnabled;

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

    /**
     * 用户 ID（Python 字段名 userId，默认 "default"）。
     */
    @JsonProperty("userId")
    private String userId = "default";
}
