package com.intellitutor.scut.modules.user.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码验证码请求。
 *
 * <p>
 * 验证当前密码后，向指定渠道（邮箱或手机）发送验证码。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
public class ChangePasswordVerifyReq {

    /** 当前密码（身份确认） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度须在 8~64 位之间")
    private String password;

    /**
     * 验证码发送渠道：{@code email} 或 {@code phone}。
     * <p>
     * 对应将验证码发送至用户绑定的邮箱或手机。
     */
    @NotBlank(message = "验证码渠道不能为空")
    @Pattern(regexp = "^(email|phone)$", message = "type 只能为 email 或 phone")
    private String type;
}
