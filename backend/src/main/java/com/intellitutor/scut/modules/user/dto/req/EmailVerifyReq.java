package com.intellitutor.scut.modules.user.dto.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 邮箱验证码请求（登录/注册通用）。
 *
 * <p>
 * 前端调用 {@code /v1/auth/login_email_verify} 或
 * {@code /v1/auth/register_email_verify} 时使用。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
public class EmailVerifyReq {

    /** 邮箱地址 */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 密码（注册时为设定密码，登录时为登录密码） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度须在 8~64 位之间")
    private String password;
}
