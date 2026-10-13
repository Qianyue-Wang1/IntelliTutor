package com.intellitutor.scut.modules.user.controller;

import com.intellitutor.scut.common.results.Result;
import com.intellitutor.scut.modules.user.dto.req.*;
import com.intellitutor.scut.modules.user.dto.vo.LoginVO;
import com.intellitutor.scut.modules.user.dto.vo.TokenRefreshVO;
import com.intellitutor.scut.modules.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 鉴权控制器。
 *
 * <p>
 * 处理用户注册、登录以及 Token 刷新等公开接口，不需要 JWT 鉴权。
 * 路由前缀为 {@code /v1/auth}（服务 context-path 为 {@code /api}，
 * 因此完整路径为 {@code /api/v1/auth/...}）。
 *
 * <p>
 * <b>安全设计要点：</b>
 * <ol>
 * <li>登录前先验证密码，通过后才发送验证码（避免被利用短信轰炸）</li>
 * <li>注册前校验邮箱/手机号唯一性，验证码由外部服务管理</li>
 * <li>密码不会在日志或响应中暴露</li>
 * </ol>
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    // ======================== 注册验证码 ========================

    /**
     * 获取注册邮箱验证码。
     *
     * <p>
     * 校验邮箱未注册后发送验证码邮件。
     *
     * @param req 邮箱 + 密码
     * @return 空数据，仅表示成功
     */
    @PostMapping("/register_email_verify")
    public Result<Void> registerEmailVerify(@RequestBody @Valid EmailVerifyReq req) {
        userService.sendRegisterEmailCode(req);
        return Result.ok();
    }

    /**
     * 获取注册手机验证码。
     *
     * <p>
     * 校验手机号未注册后发送短信验证码。
     *
     * @param req 手机号 + 密码
     * @return 空数据，仅表示成功
     */
    @PostMapping("/register_phone_verify")
    public Result<Void> registerPhoneVerify(@RequestBody @Valid PhoneVerifyReq req) {
        userService.sendRegisterPhoneCode(req);
        return Result.ok();
    }

    /**
     * 用户注册。
     *
     * <p>
     * 验证码校验通过后创建用户，并返回双 Token + 用户信息。
     *
     * @param req 邮箱/手机号 + 密码 + 验证码
     * @return 登录凭证
     */
    @PostMapping("/register")
    public Result<LoginVO> register(@RequestBody @Valid RegisterReq req) {
        return Result.ok(userService.register(req));
    }

    // ======================== 登录验证码 ========================

    /**
     * 获取登录邮箱验证码。
     *
     * <p>
     * 验证邮箱存在且密码正确后发送验证码邮件。
     *
     * @param req 邮箱 + 密码
     * @return 空数据
     */
    @PostMapping("/login_email_verify")
    public Result<Void> loginEmailVerify(@RequestBody @Valid EmailVerifyReq req) {
        userService.sendLoginEmailCode(req);
        return Result.ok();
    }

    /**
     * 获取登录手机验证码。
     *
     * <p>
     * 验证手机号存在且密码正确后发送短信验证码。
     *
     * @param req 手机号 + 密码
     * @return 空数据
     */
    @PostMapping("/login_phone_verify")
    public Result<Void> loginPhoneVerify(@RequestBody @Valid PhoneVerifyReq req) {
        userService.sendLoginPhoneCode(req);
        return Result.ok();
    }

    /**
     * 用户登录。
     *
     * <p>
     * 密码 + 验证码双重验证通过后签发 Token。
     *
     * @param req 邮箱/手机号 + 密码 + 验证码
     * @return 登录凭证
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody @Valid LoginReq req) {
        return Result.ok(userService.login(req));
    }

    /** Formal-study account/password login. No e-mail, phone or OTP is involved. */
    @PostMapping("/experiment-login")
    public Result<LoginVO> experimentLogin(@RequestBody @Valid ExperimentLoginReq req) {
        return Result.ok(userService.experimentLogin(req));
    }

    // ======================== Token 刷新 ========================

    /**
     * 刷新 Access Token（滚动刷新，旧 Refresh Token 一次性失效）。
     *
     * @param req Refresh Token
     * @return 新 Token 对
     */
    @PostMapping("/refresh")
    public Result<TokenRefreshVO> refresh(@RequestBody @Valid RefreshTokenReq req) {
        return Result.ok(userService.refreshToken(req));
    }
}
