package com.intellitutor.scut.modules.user.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 修改手机号验证码请求（向新手机号发送验证码）。
 *
 * <p>
 * 流程：
 * <ol>
 * <li>若用户已有手机号，先调用 {@code verify_original_phone} 获得原手机验证码</li>
 * <li>调用本接口时携带原手机验证码 + 新手机号，服务端校验后向新手机号发送验证码</li>
 * </ol>
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
public class ChangePhoneVerifyReq {

    /** 新手机号 */
    @NotBlank(message = "新手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String newPhoneNumber;

    /** 原手机验证码（用户已绑定手机号时必填） */
    private String originalVerifyCode;
}
