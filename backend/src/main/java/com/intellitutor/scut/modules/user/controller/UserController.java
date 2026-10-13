package com.intellitutor.scut.modules.user.controller;

import com.intellitutor.scut.common.results.Result;
import com.intellitutor.scut.modules.user.dto.req.*;
import com.intellitutor.scut.modules.user.dto.vo.UserInfoVO;
import com.intellitutor.scut.modules.user.dto.vo.UserVO;
import com.intellitutor.scut.modules.user.service.UserService;
import com.intellitutor.scut.modules.experiment.service.ExperimentAccessService;
import com.intellitutor.scut.common.utils.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户信息控制器。
 *
 * <p>
 * 处理用户个人中心的查询与修改操作，所有接口均需 JWT 鉴权。
 * 路由前缀为 {@code /v1/user}（完整路径 {@code /api/v1/user/...}）。
 *
 * <p>
 * <b>修改手机号/邮箱流程：</b>
 * <ol>
 * <li>若已绑定原联系方式 → 调用 {@code verify_original_*} 验证身份</li>
 * <li>调用 {@code change_*_verify} 向新联系方式发送验证码</li>
 * <li>调用 {@code change_user_info} 携带两个验证码完成变更</li>
 * </ol>
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final ExperimentAccessService experimentAccess;

    // ======================== 查询 ========================

    /**
     * 获取用户详细信息（含学习统计数据）。
     *
     * @param userId 用户 ID（路径变量）
     * @return 用户详细信息
     */
    @GetMapping("/{userId}")
    public Result<UserInfoVO> getUserInfo(@PathVariable String userId) {
        return Result.ok(userService.getUserInfo(userId));
    }

    // ======================== 验证原联系方式 ========================

    /**
     * 验证原手机号（向当前绑定手机发送验证码）。
     *
     * <p>
     * 验证密码后，向用户已绑定的手机号发送验证码，
     * 用于后续修改手机号时作为 {@code original_verify_code}。
     *
     * @param userId 用户 ID
     * @param req    当前密码
     * @return 空数据
     */
    @PostMapping("/{userId}/verify_original_phone")
    public Result<Void> verifyOriginalPhone(@PathVariable String userId,
            @RequestBody @Valid VerifyOriginalReq req) {
        guardExperimentStudent();
        userService.sendOriginalPhoneCode(userId, req);
        return Result.ok();
    }

    /**
     * 验证原邮箱（向当前绑定邮箱发送验证码）。
     *
     * <p>
     * 验证密码后，向用户已绑定的邮箱发送验证码，
     * 用于后续修改邮箱时作为 {@code original_verify_code}。
     *
     * @param userId 用户 ID
     * @param req    当前密码
     * @return 空数据
     */
    @PostMapping("/{userId}/verify_original_email")
    public Result<Void> verifyOriginalEmail(@PathVariable String userId,
            @RequestBody @Valid VerifyOriginalReq req) {
        guardExperimentStudent();
        userService.sendOriginalEmailCode(userId, req);
        return Result.ok();
    }

    // ======================== 向新联系方式发送验证码 ========================

    /**
     * 获取修改手机号验证码（发送到新手机号）。
     *
     * <p>
     * 若用户已有手机号，需携带 {@code original_verify_code}；
     * 若用户未绑定手机号，可直接发送。
     *
     * @param userId 用户 ID
     * @param req    新手机号 + 原验证码（可选）
     * @return 空数据
     */
    @PostMapping("/{userId}/change_phone_verify")
    public Result<Void> changePhoneVerify(@PathVariable String userId,
            @RequestBody @Valid ChangePhoneVerifyReq req) {
        guardExperimentStudent();
        userService.sendChangePhoneCode(userId, req);
        return Result.ok();
    }

    /**
     * 获取修改邮箱验证码（发送到新邮箱）。
     *
     * <p>
     * 若用户已有邮箱，需携带 {@code original_verify_code}；
     * 若用户未绑定邮箱，可直接发送。
     *
     * @param userId 用户 ID
     * @param req    新邮箱 + 原验证码（可选）
     * @return 空数据
     */
    @PostMapping("/{userId}/change_email_verify")
    public Result<Void> changeEmailVerify(@PathVariable String userId,
            @RequestBody @Valid ChangeEmailVerifyReq req) {
        guardExperimentStudent();
        userService.sendChangeEmailCode(userId, req);
        return Result.ok();
    }

    // ======================== 修改密码验证码 ========================

    /**
     * 获取修改密码验证码。
     *
     * <p>
     * 验证当前密码后，根据 type 向绑定邮箱或手机发送验证码。
     *
     * @param userId 用户 ID
     * @param req    当前密码 + 验证码渠道
     * @return 空数据
     */
    @PostMapping("/{userId}/change_password_verify")
    public Result<Void> changePasswordVerify(@PathVariable String userId,
            @RequestBody @Valid ChangePasswordVerifyReq req) {
        guardExperimentStudent();
        userService.sendChangePasswordCode(userId, req);
        return Result.ok();
    }

    // ======================== 更新用户信息 ========================

    /**
     * 更新用户信息（昵称/手机号/邮箱）。
     *
     * <p>
     * 修改手机号/邮箱时需携带 {@code original_verify_code} 和 {@code new_verify_code}，
     * 修改昵称无需验证码。
     *
     * @param userId 用户 ID
     * @param req    修改项
     * @return 更新后的用户基础信息
     */
    @PostMapping("/{userId}/change_user_info")
    public Result<UserVO> updateUserInfo(@PathVariable String userId,
            @RequestBody @Valid ChangeUserInfoReq req) {
        guardExperimentStudent();
        return Result.ok(userService.updateUserInfo(userId, req));
    }

    /**
     * 更新用户头像（Base64 存储）。
     *
     * <p>
     * 前端将图片编码为 Base64 字符串传入，后端直接存入数据库。
     *
     * @param userId 用户 ID
     * @param req    Base64 编码的头像数据
     * @return 新头像值
     */
    @PostMapping("/{userId}/change_avatar")
    public Result<Map<String, String>> changeAvatar(@PathVariable String userId,
            @RequestBody @Valid ChangeAvatarReq req) {
        guardExperimentStudent();
        String avatar = userService.updateAvatar(userId, req);
        return Result.ok(Map.of("avatar", avatar));
    }

    // ======================== 修改密码 ========================

    /**
     * 修改密码。
     *
     * <p>
     * 支持两种方式：
     * <ol>
     * <li>旧密码 + 验证码</li>
     * <li>仅验证码（忘记密码场景）</li>
     * </ol>
     *
     * @param userId 用户 ID
     * @param req    密码修改参数
     * @return 空数据
     */
    @PostMapping("/{userId}/change_password")
    public Result<Void> changePassword(@PathVariable String userId,
            @RequestBody @Valid ChangePasswordReq req) {
        guardExperimentStudent();
        userService.changePassword(userId, req);
        return Result.ok();
    }

    private void guardExperimentStudent() {
        experimentAccess.rejectLegacyTutorForExperimentStudent(UserContext.getUserId());
    }
}
