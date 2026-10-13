package com.intellitutor.scut.modules.user.dto.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新用户信息请求。
 *
 * <p>
 * 支持修改昵称（无需验证码）、修改手机号/邮箱（需两步验证码确认）。
 * 所有字段均为可选，按需传入。
 *
 * <p>
 * <b>修改手机号/邮箱流程：</b>
 * <ol>
 * <li>用户先验证原联系方式获取 {@code originalVerifyCode}</li>
 * <li>再对新联系方式获取 {@code newVerifyCode}</li>
 * <li>最终调用本接口携带两个验证码完成变更</li>
 * </ol>
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Data
public class ChangeUserInfoReq {

    /** 新昵称（无需验证码） */
    @Size(max = 32, message = "昵称长度不能超过 32 位")
    @Pattern(regexp = "^(?:[^<>\\p{Cntrl}]{1,32})?$", message = "Nickname must not contain HTML tags or control characters")
    private String newNickname;

    /** 新手机号（需配合 original_verify_code + new_verify_code） */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String newPhoneNumber;

    /** 新邮箱（需配合 original_verify_code + new_verify_code） */
    @Email(message = "邮箱格式不正确")
    private String newEmail;

    /** 原联系方式的验证码（修改已绑定手机号/邮箱时必填） */
    private String originalVerifyCode;

    /** 新联系方式的验证码（修改手机号/邮箱时必填） */
    private String newVerifyCode;
}
