package com.intellitutor.scut.modules.user.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求。
 *
 * <p>
 * 支持两种方式：
 * <ol>
 * <li>通过旧密码 + 验证码修改</li>
 * <li>仅通过验证码修改（忘记密码场景，{@code oldPassword} 为空）</li>
 * </ol>
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
public class ChangePasswordReq {

    /** 旧密码（可选，忘记密码场景无需提供） */
    @Size(min = 8, max = 64, message = "密码长度须在 8~64 位之间")
    private String oldPassword;

    /** 新密码 */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度须在 8~64 位之间")
    private String newPassword;

    /**
     * 验证码渠道类型：{@code email} 或 {@code phone}。
     * <p>
     * 必须与之前调用 change_password_verify 时使用的渠道一致。
     */
    @NotBlank(message = "验证码渠道不能为空")
    @Pattern(regexp = "^(email|phone)$", message = "type 只能为 email 或 phone")
    private String type;

    /** 验证码 */
    @NotBlank(message = "验证码不能为空")
    private String verifyCode;
}
