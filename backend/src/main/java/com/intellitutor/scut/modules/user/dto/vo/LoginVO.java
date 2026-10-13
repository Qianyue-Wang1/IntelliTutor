package com.intellitutor.scut.modules.user.dto.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 登录/注册成功响应视图（含双 Token + 用户基础信息）。
 *
 * <p>
 * 前端 {@code LoginResponse} / {@code RegisterResponse} 共用此结构。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
@Builder
public class LoginVO {

    /** JWT Access Token */
    private String accessToken;

    /** Refresh Token（UUID，7 天有效） */
    private String refreshToken;

    /** 用户基础信息 */
    private UserVO user;
}
