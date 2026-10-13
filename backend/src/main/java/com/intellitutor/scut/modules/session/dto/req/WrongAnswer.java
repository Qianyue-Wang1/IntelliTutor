package com.intellitutor.scut.modules.session.dto.req;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 错题记录，用于 explanation 的 UpdateState / SessionState 请求/响应。
 * Python Pydantic 字段：step_number, operation, wrong_option（全部 snake_case）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class WrongAnswer {

    /** 步骤编号，如 "步骤1" */
    private String stepNumber;

    /** 具体操作描述 */
    private String operation;

    /** 用户选择的错误选项文本 */
    private String wrongOption;
}
