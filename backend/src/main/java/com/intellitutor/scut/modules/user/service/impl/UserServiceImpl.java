package com.intellitutor.scut.modules.user.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.intellitutor.scut.common.constant.MqConstants;
import com.intellitutor.scut.common.exception.BusinessException;
import com.intellitutor.scut.common.results.ResultCode;
import com.intellitutor.scut.common.utils.JwtUtil;
import com.intellitutor.scut.common.utils.UserContext;
import com.intellitutor.scut.config.FeatureProperties;
import com.intellitutor.scut.modules.experiment.service.ExperimentAccessService;
import com.intellitutor.scut.modules.experiment.service.ExperimentEventService;
import com.intellitutor.scut.modules.user.dto.req.*;
import com.intellitutor.scut.modules.user.dto.vo.*;
import com.intellitutor.scut.modules.user.entity.User;
import com.intellitutor.scut.modules.user.mapper.UserMapper;
import com.intellitutor.scut.modules.user.service.EmailVerifyCodeService;
import com.intellitutor.scut.modules.user.service.SmsVerifyCodeService;
import com.intellitutor.scut.modules.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 用户服务实现类。
 *
 * <p>
 * 核心职责：
 * <ol>
 * <li>注册/登录流程中的密码校验与验证码发放</li>
 * <li>JWT Access Token 签发与 Refresh Token 管理（Redis 存储）</li>
 * <li>用户个人信息的查询与变更（含双验证码流程）</li>
 * </ol>
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;
    private final SmsVerifyCodeService smsVerifyCodeService;
    private final EmailVerifyCodeService emailVerifyCodeService;
    private final FeatureProperties features;
    private final ExperimentAccessService experimentAccessService;
    private final ExperimentEventService experimentEventService;

    @org.springframework.beans.factory.annotation.Autowired
    public UserServiceImpl(UserMapper userMapper, JwtUtil jwtUtil, StringRedisTemplate stringRedisTemplate,
                           SmsVerifyCodeService smsVerifyCodeService, EmailVerifyCodeService emailVerifyCodeService,
                           FeatureProperties features, ExperimentAccessService experimentAccessService,
                           ExperimentEventService experimentEventService) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.stringRedisTemplate = stringRedisTemplate;
        this.smsVerifyCodeService = smsVerifyCodeService;
        this.emailVerifyCodeService = emailVerifyCodeService;
        this.features = features;
        this.experimentAccessService = experimentAccessService;
        this.experimentEventService = experimentEventService;
    }

    /** Retained for focused legacy unit tests and non-experiment embedding. */
    public UserServiceImpl(UserMapper userMapper, JwtUtil jwtUtil, StringRedisTemplate stringRedisTemplate,
                           SmsVerifyCodeService smsVerifyCodeService, EmailVerifyCodeService emailVerifyCodeService) {
        this(userMapper, jwtUtil, stringRedisTemplate, smsVerifyCodeService, emailVerifyCodeService,
                new FeatureProperties(), null, null);
    }

    // ====================================================================
    // 注册流程
    // ====================================================================

    @Override
    public void sendRegisterEmailCode(EmailVerifyReq req) {
        requireRegistrationEnabled();
        if (existsByEmail(req.getEmail())) {
            throw new BusinessException(ResultCode.EMAIL_ALREADY_BOUND);
        }
        emailVerifyCodeService.sendVerifyCode(req.getEmail(), "register_email");
    }

    @Override
    public void sendRegisterPhoneCode(PhoneVerifyReq req) {
        requireRegistrationEnabled();
        if (existsByPhone(req.getPhoneNumber())) {
            throw new BusinessException(ResultCode.PHONE_ALREADY_BOUND);
        }
        smsVerifyCodeService.sendVerifyCode(req.getPhoneNumber());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginVO register(RegisterReq req) {
        requireRegistrationEnabled();
        String email = req.getEmail();
        String phone = req.getPhoneNumber();
        validateContactPresent(email, phone);

        // 验证码校验
        if (!verifyCode(email, phone, req.getVerifyCode(), "register")) {
            throw new BusinessException(ResultCode.VERIFY_CODE_INVALID);
        }

        // 再次校验唯一性（防并发）
        if (StringUtils.hasText(email) && existsByEmail(email)) {
            throw new BusinessException(ResultCode.EMAIL_ALREADY_BOUND);
        }
        if (StringUtils.hasText(phone) && existsByPhone(phone)) {
            throw new BusinessException(ResultCode.PHONE_ALREADY_BOUND);
        }

        User user = new User();
        user.setEmail(email);
        user.setPhoneNumber(phone);
        user.setPassword(BCrypt.hashpw(req.getPassword(), BCrypt.gensalt()));
        user.setNickname(generateDefaultNickname());
        user.setRoles("STUDENT");
        user.setSolvedProblems(0);
        user.setStudyDays(0);
        user.setMasteredKnowledgePoints(0);
        user.setWeeklyStudyQuestions(0);
        user.setWeeklyMasteredKnowledgePoints(0);
        user.setAccuracy(BigDecimal.ZERO);
        user.setLastLogin(LocalDateTime.now());
        user.setDeleted(0);
        userMapper.insert(user);

        log.info("[UserService] 用户注册成功: id={}", user.getId());
        return buildLoginVO(user);
    }

    // ====================================================================
    // 登录流程
    // ====================================================================

    @Override
    public void sendLoginEmailCode(EmailVerifyReq req) {
        requireClassicLoginEnabled();
        User user = findByEmail(req.getEmail());
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_EXIST);
        }
        validatePassword(req.getPassword(), user.getPassword());
        emailVerifyCodeService.sendVerifyCode(req.getEmail(), "login_email");
    }

    @Override
    public void sendLoginPhoneCode(PhoneVerifyReq req) {
        requireClassicLoginEnabled();
        User user = findByPhone(req.getPhoneNumber());
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_EXIST);
        }
        validatePassword(req.getPassword(), user.getPassword());
        smsVerifyCodeService.sendVerifyCode(req.getPhoneNumber());
    }

    @Override
    public LoginVO login(LoginReq req) {
        requireClassicLoginEnabled();
        String email = req.getEmail();
        String phone = req.getPhoneNumber();
        validateContactPresent(email, phone);

        User user = StringUtils.hasText(email) ? findByEmail(email) : findByPhone(phone);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_EXIST);
        }
        validatePassword(req.getPassword(), user.getPassword());

        if (!verifyCode(email, phone, req.getVerifyCode(), "login")) {
            throw new BusinessException(ResultCode.VERIFY_CODE_INVALID);
        }

        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getLastLogin, LocalDateTime.now()));

        log.info("[UserService] 用户登录成功: id={}", user.getId());
        return buildLoginVO(user);
    }

    @Override
    public LoginVO experimentLogin(ExperimentLoginReq req) {
        if (!features.isExperimentModeEnabled()) {
            throw new BusinessException(ResultCode.FEATURE_DISABLED, "实验登录当前未启用");
        }
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getLoginAccount, req.getLoginAccount()));
        boolean admin = user != null && parseRoles(user.getRoles()).contains("ADMIN");
        if (user == null || (!admin && !experimentAccessService.hasAssignment(user.getId()))) {
            throw new BusinessException(ResultCode.USER_NOT_EXIST);
        }
        validatePassword(req.getPassword(), user.getPassword());
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getLastLogin, LocalDateTime.now()));
        if (!admin) {
            experimentEventService.recordSystem(user.getId(), "LOGIN", java.util.Map.of("method", "EXPERIMENT_ACCOUNT"));
        }
        return buildLoginVO(user);
    }

    @Override
    public LoginVO issueLocalExperimentLogin(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null || (user.getDeleted() != null && user.getDeleted() != 0)) {
            throw new BusinessException(ResultCode.USER_NOT_EXIST);
        }
        return buildLoginVO(user);
    }

    // ====================================================================
    // Token 管理
    // ====================================================================

    @Override
    public TokenRefreshVO refreshToken(RefreshTokenReq req) {
        String refreshToken = req.getRefreshToken();
        String redisKey = MqConstants.REFRESH_TOKEN_PREFIX + refreshToken;

        String userId = stringRedisTemplate.opsForValue().get(redisKey);
        if (userId == null) {
            throw new BusinessException(ResultCode.TOKEN_EXPIRED, "Refresh Token 已过期或无效");
        }
        stringRedisTemplate.delete(redisKey);

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_EXIST);
        }

        List<String> roles = parseRoles(user.getRoles());
        String newAccessToken = jwtUtil.generateAccessToken(user.getId(), roles);
        String newRefreshToken = generateAndCacheRefreshToken(user.getId());

        return TokenRefreshVO.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .build();
    }

    // ====================================================================
    // 用户信息查询
    // ====================================================================

    @Override
    public UserInfoVO getUserInfo(String userId) {
        validateCurrentUser(userId);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_EXIST);
        }
        return buildUserInfoVO(user);
    }

    // ====================================================================
    // 验证原联系方式（向已绑定的手机/邮箱发送验证码）
    // ====================================================================

    @Override
    public void sendOriginalPhoneCode(String userId, VerifyOriginalReq req) {
        validateCurrentUser(userId);
        User user = getAndValidateUser(userId, req.getPassword());

        if (!StringUtils.hasText(user.getPhoneNumber())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该账号未绑定手机号");
        }
        smsVerifyCodeService.sendVerifyCode(user.getPhoneNumber());
        log.info("[UserService] 已向原手机号发送验证码: userId={}", userId);
    }

    @Override
    public void sendOriginalEmailCode(String userId, VerifyOriginalReq req) {
        validateCurrentUser(userId);
        User user = getAndValidateUser(userId, req.getPassword());

        if (!StringUtils.hasText(user.getEmail())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该账号未绑定邮箱");
        }
        emailVerifyCodeService.sendVerifyCode(user.getEmail(), "verify_original_email");
        log.info("[UserService] 已向原邮箱发送验证码: userId={}", userId);
    }

    // ====================================================================
    // 向新联系方式发送验证码
    // ====================================================================

    @Override
    public void sendChangePhoneCode(String userId, ChangePhoneVerifyReq req) {
        validateCurrentUser(userId);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_EXIST);
        }

        // 如果用户已绑定手机号，必须先验证原手机验证码
        if (StringUtils.hasText(user.getPhoneNumber())) {
            if (!StringUtils.hasText(req.getOriginalVerifyCode())) {
                throw new BusinessException(ResultCode.PARAM_MISSING, "已绑定手机号，需要提供原手机验证码");
            }
            boolean originalOk = smsVerifyCodeService.checkVerifyCode(
                    user.getPhoneNumber(), req.getOriginalVerifyCode());
            if (!originalOk) {
                throw new BusinessException(ResultCode.VERIFY_CODE_INVALID, "原手机验证码错误或已过期");
            }
        }

        // 校验新手机号未被占用
        if (existsByPhone(req.getNewPhoneNumber())) {
            throw new BusinessException(ResultCode.PHONE_ALREADY_BOUND);
        }

        smsVerifyCodeService.sendVerifyCode(req.getNewPhoneNumber());
        log.info("[UserService] 已向新手机号发送验证码: userId={}, newPhone={}", userId, req.getNewPhoneNumber());
    }

    @Override
    public void sendChangeEmailCode(String userId, ChangeEmailVerifyReq req) {
        validateCurrentUser(userId);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_EXIST);
        }

        // 如果用户已绑定邮箱，必须先验证原邮箱验证码
        if (StringUtils.hasText(user.getEmail())) {
            if (!StringUtils.hasText(req.getOriginalVerifyCode())) {
                throw new BusinessException(ResultCode.PARAM_MISSING, "已绑定邮箱，需要提供原邮箱验证码");
            }
            boolean originalOk = emailVerifyCodeService.checkVerifyCode(
                    user.getEmail(), req.getOriginalVerifyCode(), "verify_original_email");
            if (!originalOk) {
                throw new BusinessException(ResultCode.VERIFY_CODE_INVALID, "原邮箱验证码错误或已过期");
            }
        }

        // 校验新邮箱未被占用
        if (existsByEmail(req.getNewEmail())) {
            throw new BusinessException(ResultCode.EMAIL_ALREADY_BOUND);
        }

        emailVerifyCodeService.sendVerifyCode(req.getNewEmail(), "change_email");
        log.info("[UserService] 已向新邮箱发送验证码: userId={}, newEmail={}", userId, req.getNewEmail());
    }

    // ====================================================================
    // 修改密码验证码
    // ====================================================================

    @Override
    public void sendChangePasswordCode(String userId, ChangePasswordVerifyReq req) {
        validateCurrentUser(userId);
        User user = getAndValidateUser(userId, req.getPassword());

        String type = req.getType();
        if ("email".equals(type)) {
            if (!StringUtils.hasText(user.getEmail())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "该账号未绑定邮箱，无法通过邮箱验证");
            }
            emailVerifyCodeService.sendVerifyCode(user.getEmail(), "change_password_email");
        } else {
            if (!StringUtils.hasText(user.getPhoneNumber())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "该账号未绑定手机号，无法通过手机验证");
            }
            smsVerifyCodeService.sendVerifyCode(user.getPhoneNumber());
        }
    }

    // ====================================================================
    // 更新用户信息
    // ====================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateUserInfo(String userId, ChangeUserInfoReq req) {
        validateCurrentUser(userId);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_EXIST);
        }

        LambdaUpdateWrapper<User> updateWrapper = new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId);
        boolean needUpdate = false;

        // 修改昵称（无需验证码）
        if (StringUtils.hasText(req.getNewNickname())) {
            updateWrapper.set(User::getNickname, req.getNewNickname());
            user.setNickname(req.getNewNickname());
            needUpdate = true;
        }

        // 修改手机号（需原验证码 + 新验证码）
        if (StringUtils.hasText(req.getNewPhoneNumber())) {
            validateNewVerifyCode(req.getNewVerifyCode(), "修改手机号需要新手机验证码");

            // 若已有手机号，校验原手机验证码
            if (StringUtils.hasText(user.getPhoneNumber())) {
                validateOriginalVerifyCode(req.getOriginalVerifyCode(), "修改手机号需要原手机验证码");
                boolean originalOk = smsVerifyCodeService.checkVerifyCode(
                        user.getPhoneNumber(), req.getOriginalVerifyCode());
                if (!originalOk) {
                    throw new BusinessException(ResultCode.VERIFY_CODE_INVALID, "原手机验证码错误或已过期");
                }
            }

            // 校验新手机验证码
            boolean newOk = smsVerifyCodeService.checkVerifyCode(
                    req.getNewPhoneNumber(), req.getNewVerifyCode());
            if (!newOk) {
                throw new BusinessException(ResultCode.VERIFY_CODE_INVALID, "新手机验证码错误或已过期");
            }

            if (existsByPhone(req.getNewPhoneNumber())) {
                throw new BusinessException(ResultCode.PHONE_ALREADY_BOUND);
            }
            updateWrapper.set(User::getPhoneNumber, req.getNewPhoneNumber());
            user.setPhoneNumber(req.getNewPhoneNumber());
            needUpdate = true;
        }

        // 修改邮箱（需原验证码 + 新验证码）
        if (StringUtils.hasText(req.getNewEmail())) {
            validateNewVerifyCode(req.getNewVerifyCode(), "修改邮箱需要新邮箱验证码");

            // 若已有邮箱，校验原邮箱验证码
            if (StringUtils.hasText(user.getEmail())) {
                validateOriginalVerifyCode(req.getOriginalVerifyCode(), "修改邮箱需要原邮箱验证码");
                boolean originalOk = emailVerifyCodeService.checkVerifyCode(
                        user.getEmail(), req.getOriginalVerifyCode(), "verify_original_email");
                if (!originalOk) {
                    throw new BusinessException(ResultCode.VERIFY_CODE_INVALID, "原邮箱验证码错误或已过期");
                }
            }

            // 校验新邮箱验证码
            boolean newOk = emailVerifyCodeService.checkVerifyCode(
                    req.getNewEmail(), req.getNewVerifyCode(), "change_email");
            if (!newOk) {
                throw new BusinessException(ResultCode.VERIFY_CODE_INVALID, "新邮箱验证码错误或已过期");
            }

            if (existsByEmail(req.getNewEmail())) {
                throw new BusinessException(ResultCode.EMAIL_ALREADY_BOUND);
            }
            updateWrapper.set(User::getEmail, req.getNewEmail());
            user.setEmail(req.getNewEmail());
            needUpdate = true;
        }

        if (needUpdate) {
            userMapper.update(null, updateWrapper);
        }

        return buildUserVO(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String updateAvatar(String userId, ChangeAvatarReq req) {
        validateCurrentUser(userId);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_EXIST);
        }

        String avatar = req.getAvatar();
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getAvatar, avatar));

        log.info("[UserService] 头像更新成功: userId={}", userId);
        return avatar;
    }

    // ====================================================================
    // 修改密码
    // ====================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(String userId, ChangePasswordReq req) {
        validateCurrentUser(userId);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_EXIST);
        }

        // 校验旧密码（如果提供）
        if (StringUtils.hasText(req.getOldPassword())) {
            validatePassword(req.getOldPassword(), user.getPassword());
        }

        // 校验验证码
        boolean codeOk;
        if ("email".equals(req.getType())) {
            if (!StringUtils.hasText(user.getEmail())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "该账号未绑定邮箱");
            }
            codeOk = emailVerifyCodeService.checkVerifyCode(
                    user.getEmail(), req.getVerifyCode(), "change_password_email");
        } else {
            if (!StringUtils.hasText(user.getPhoneNumber())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "该账号未绑定手机号");
            }
            codeOk = smsVerifyCodeService.checkVerifyCode(user.getPhoneNumber(), req.getVerifyCode());
        }

        if (!codeOk) {
            throw new BusinessException(ResultCode.VERIFY_CODE_INVALID);
        }

        String newPasswordHash = BCrypt.hashpw(req.getNewPassword(), BCrypt.gensalt());
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getPassword, newPasswordHash));

        log.info("[UserService] 密码修改成功: userId={}", userId);
    }

    // ====================================================================
    // 私有辅助方法
    // ====================================================================

    private void validateCurrentUser(String userId) {
        String currentUserId = UserContext.getUserId();
        if (!StringUtils.hasText(currentUserId) || !currentUserId.equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权限访问其他用户资源");
        }
    }

    private LoginVO buildLoginVO(User user) {
        List<String> roles = parseRoles(user.getRoles());
        String accessToken = jwtUtil.generateAccessToken(user.getId(), roles);
        String refreshToken = generateAndCacheRefreshToken(user.getId());

        return LoginVO.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(buildUserVO(user))
                .build();
    }

    private String generateAndCacheRefreshToken(String userId) {
        String refreshToken = UUID.randomUUID().toString().replace("-", "");
        String redisKey = MqConstants.REFRESH_TOKEN_PREFIX + refreshToken;
        stringRedisTemplate.opsForValue().set(redisKey, userId,
                MqConstants.REFRESH_TOKEN_TTL_SECONDS, TimeUnit.SECONDS);
        return refreshToken;
    }

    private boolean verifyCode(String email, String phone, String code, String bizPrefix) {
        if (StringUtils.hasText(email)) {
            return emailVerifyCodeService.checkVerifyCode(email, code, bizPrefix + "_email");
        }
        if (StringUtils.hasText(phone)) {
            return smsVerifyCodeService.checkVerifyCode(phone, code);
        }
        return false;
    }

    private void validatePassword(String rawPassword, String encodedPassword) {
        if (!BCrypt.checkpw(rawPassword, encodedPassword)) {
            throw new BusinessException(ResultCode.PASSWORD_ERROR);
        }
    }

    private void validateContactPresent(String email, String phone) {
        if (!StringUtils.hasText(email) && !StringUtils.hasText(phone)) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "邮箱和手机号至少提供一个");
        }
    }

    private void validateOriginalVerifyCode(String code, String errorMsg) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(ResultCode.PARAM_MISSING, errorMsg);
        }
    }

    private void validateNewVerifyCode(String code, String errorMsg) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(ResultCode.PARAM_MISSING, errorMsg);
        }
    }

    private User getAndValidateUser(String userId, String rawPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_EXIST);
        }
        validatePassword(rawPassword, user.getPassword());
        return user;
    }

    private User findByEmail(String email) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email));
    }

    private User findByPhone(String phone) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhoneNumber, phone));
    }

    private boolean existsByEmail(String email) {
        return userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email)) > 0;
    }

    private boolean existsByPhone(String phone) {
        return userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getPhoneNumber, phone)) > 0;
    }

    private UserVO buildUserVO(User user) {
        return UserVO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .loginAccount(user.getLoginAccount())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .mustChangePassword(Boolean.TRUE.equals(user.getMustChangePassword()))
                .roles(parseRoles(user.getRoles()))
                .build();
    }

    private void requireRegistrationEnabled() {
        if (features.isExperimentModeEnabled()) {
            throw new BusinessException(ResultCode.FEATURE_DISABLED, "正式实验期间不开放自行注册");
        }
    }

    private void requireClassicLoginEnabled() {
        if (features.isExperimentModeEnabled()) {
            throw new BusinessException(ResultCode.EXPERIMENT_ACCOUNT_REQUIRED);
        }
    }

    private UserInfoVO buildUserInfoVO(User user) {
        return UserInfoVO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .solvedProblems(user.getSolvedProblems())
                .studyDays(user.getStudyDays())
                .masteredKnowledgePoints(user.getMasteredKnowledgePoints())
                .weeklyStudyQuestions(user.getWeeklyStudyQuestions())
                .weeklyMasteredKnowledgePoints(user.getWeeklyMasteredKnowledgePoints())
                .accuracy(user.getAccuracy())
                .roles(parseRoles(user.getRoles()))
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .lastLogin(user.getLastLogin())
                .build();
    }

    private List<String> parseRoles(String roles) {
        if (!StringUtils.hasText(roles)) {
            return List.of("STUDENT");
        }
        return Arrays.asList(roles.split(","));
    }

    private String generateDefaultNickname() {
        return "学员" + UUID.randomUUID().toString().substring(0, 8);
    }
}
