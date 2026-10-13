package com.intellitutor.scut.modules.user.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ExperimentLoginReq {
    @NotBlank(message = "登录账号不能为空")
    @Size(max = 64, message = "登录账号过长")
    private String loginAccount;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 128, message = "密码长度应为 8-128 位")
    private String password;
}
