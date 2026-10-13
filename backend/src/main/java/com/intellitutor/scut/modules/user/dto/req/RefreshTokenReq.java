package com.intellitutor.scut.modules.user.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Token 刷新请求。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
public class RefreshTokenReq {

    /** 客户端持有的 Refresh Token */
    @NotBlank(message = "refresh_token 不能为空")
    private String refreshToken;
}
