package com.intellitutor.scut.modules.user.dto.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Token 刷新响应视图。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
@Builder
public class TokenRefreshVO {

    /** 新的 JWT Access Token */
    private String accessToken;

    /** 新的 Refresh Token（可选，滚动刷新策略下返回新值） */
    private String refreshToken;
}
