package com.intellitutor.scut.modules.user.service;

import com.intellitutor.scut.modules.user.dto.req.*;
import com.intellitutor.scut.modules.user.dto.vo.*;

/**
 * 用户业务服务接口。
 *
 * <p>
 * 涵盖注册、登录、Token 刷新以及用户信息的增查改操作。
 * 所有方法均使用 {@code Req}/{@code VO} 进行参数/返回值隔离，
 * 避免实体类泄露到 API 层。
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
public interface UserService {

    // ===========================
    // 注册验证码
    // ===========================

    /**
     * 发送注册邮箱验证码。
     *
     * @param req 邮箱 + 密码
     */
    void sendRegisterEmailCode(EmailVerifyReq req);

    /**
     * 发送注册手机验证码。
     *
     * @param req 手机号 + 密码
     */
    void sendRegisterPhoneCode(PhoneVerifyReq req);

    /**
     * 用户注册（最终提交）。
     *
     * @param req 注册参数（含验证码）
     * @return 登录凭证 + 用户基础信息
     */
    LoginVO register(RegisterReq req);

    // ===========================
    // 登录验证码
    // ===========================

    /**
     * 发送登录邮箱验证码。
     *
     * @param req 邮箱 + 密码
     */
    void sendLoginEmailCode(EmailVerifyReq req);

    /**
     * 发送登录手机验证码。
     *
     * @param req 手机号 + 密码
     */
    void sendLoginPhoneCode(PhoneVerifyReq req);

    /**
     * 用户登录（最终提交）。
     *
     * @param req 登录参数（含验证码）
     * @return 登录凭证 + 用户基础信息
     */
    LoginVO login(LoginReq req);

    /** Password-only login for pre-created human-experiment accounts. */
    LoginVO experimentLogin(ExperimentLoginReq req);

    /** Issues the normal token pair for an internal local-experiment participant account. */
    LoginVO issueLocalExperimentLogin(String userId);

    // ===========================
    // Token 管理
    // ===========================

    /**
     * 刷新 Access Token（滚动刷新策略）。
     *
     * @param req Refresh Token
     * @return 新 Token 对
     */
    TokenRefreshVO refreshToken(RefreshTokenReq req);

    // ===========================
    // 用户信息查询与修改
    // ===========================

    /**
     * 获取用户详细信息。
     *
     * @param userId 用户 ID
     * @return 用户详细信息视图
     */
    UserInfoVO getUserInfo(String userId);

    /**
     * 验证原手机号（向用户已绑定的手机号发送验证码）。
     *
     * @param userId 当前用户 ID
     * @param req    当前密码
     */
    void sendOriginalPhoneCode(String userId, VerifyOriginalReq req);

    /**
     * 验证原邮箱（向用户已绑定的邮箱发送验证码）。
     *
     * @param userId 当前用户 ID
     * @param req    当前密码
     */
    void sendOriginalEmailCode(String userId, VerifyOriginalReq req);

    /**
     * 发送修改手机号验证码（发送到新手机号）。
     *
     * @param userId 当前用户 ID
     * @param req    新手机号 + 原手机验证码（可选）
     */
    void sendChangePhoneCode(String userId, ChangePhoneVerifyReq req);

    /**
     * 发送修改邮箱验证码（发送到新邮箱）。
     *
     * @param userId 当前用户 ID
     * @param req    新邮箱 + 原邮箱验证码（可选）
     */
    void sendChangeEmailCode(String userId, ChangeEmailVerifyReq req);

    /**
     * 发送修改密码验证码。
     *
     * @param userId 当前用户 ID
     * @param req    当前密码 + 渠道类型
     */
    void sendChangePasswordCode(String userId, ChangePasswordVerifyReq req);

    /**
     * 修改用户信息（昵称/手机号/邮箱）。
     *
     * @param userId 当前用户 ID
     * @param req    修改项
     * @return 更新后的用户基础信息
     */
    UserVO updateUserInfo(String userId, ChangeUserInfoReq req);

    /**
     * 更新用户头像（Base64 存储至数据库）。
     *
     * @param userId 当前用户 ID
     * @param req    Base64 编码的头像数据
     * @return 存储后的头像值
     */
    String updateAvatar(String userId, ChangeAvatarReq req);

    /**
     * 修改密码。
     *
     * @param userId 当前用户 ID
     * @param req    新密码 + 验证码
     */
    void changePassword(String userId, ChangePasswordReq req);
}
