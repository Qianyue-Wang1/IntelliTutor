package com.intellitutor.scut.modules.session.dto.req;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** POST /v1/explanation/advance 请求体。 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdvanceExplanationReq {

    @NotBlank
    private String sessionId;
}
