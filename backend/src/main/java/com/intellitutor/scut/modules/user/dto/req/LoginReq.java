package com.intellitutor.scut.modules.user.dto.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户登录请求。
 *
 * <p>
 * {@code email} 与 {@code phoneNumber} 至少提供一个，配合密码和验证码完成二步验证登录。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
public class LoginReq {

    /** 登录邮箱（与 phoneNumber 二选一） */
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 登录手机号（与 email 二选一） */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phoneNumber;

    /** 登录密码 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度须在 8~64 位之间")
    private String password;

    /** 验证码 */
    @NotBlank(message = "验证码不能为空")
    private String verifyCode;
}
