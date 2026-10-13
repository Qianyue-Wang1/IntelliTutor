package com.intellitutor.scut.modules.user.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 手机验证码请求（登录/注册通用）。
 *
 * <p>
 * 前端调用 {@code /v1/auth/login_phone_verify} 或
 * {@code /v1/auth/register_phone_verify} 时使用。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
public class PhoneVerifyReq {

    /** 手机号（中国大陆 11 位） */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phoneNumber;

    /** 密码 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度须在 8~64 位之间")
    private String password;
}
