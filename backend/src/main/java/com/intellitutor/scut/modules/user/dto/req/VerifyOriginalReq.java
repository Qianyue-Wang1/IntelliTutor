package com.intellitutor.scut.modules.user.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 验证原手机号/原邮箱请求。
 *
 * <p>
 * 用户修改绑定手机号或邮箱之前，需要先验证当前密码，
 * 服务端据此向用户已绑定的联系方式发送验证码。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
public class VerifyOriginalReq {

    /** 当前密码（身份确认） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度须在 8~64 位之间")
    private String password;
}
