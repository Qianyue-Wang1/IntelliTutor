package com.intellitutor.scut.modules.user.dto.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 修改邮箱验证码请求（向新邮箱发送验证码）。
 *
 * <p>
 * 流程：
 * <ol>
 * <li>若用户已有邮箱，先调用 {@code verify_original_email} 获得原邮箱验证码</li>
 * <li>调用本接口时携带原邮箱验证码 + 新邮箱，服务端校验后向新邮箱发送验证码</li>
 * </ol>
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
public class ChangeEmailVerifyReq {

    /** 新邮箱 */
    @NotBlank(message = "新邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String newEmail;

    /** 原邮箱验证码（用户已绑定邮箱时必填） */
    private String originalVerifyCode;
}
