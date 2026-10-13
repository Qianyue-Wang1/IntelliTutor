package com.intellitutor.scut.common.results;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一业务状态码枚举
 *
 * <p>
 * 编码规范：
 * <ul>
 * <li>2xx 通用成功</li>
 * <li>40xx 客户端错误（参数/鉴权/权限）</li>
 * <li>50xx 服务端内部错误</li>
 * <li>60xx 业务逻辑错误（会话/推理/KT）</li>
 * </ul>
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    // ===== 2xx 成功 =====
    SUCCESS(200, "操作成功"),
    ACCEPTED(202, "请求已受理，推理进行中"),

    // ===== 400x 客户端错误 =====
    PARAM_ERROR(4000, "请求参数错误"),
    PARAM_MISSING(4001, "缺少必要请求参数"),
    PARAM_TYPE_ERROR(4002, "请求参数类型不匹配"),

    UNAUTHORIZED(4010, "未登录或 Token 已失效，请重新登录"),
    TOKEN_INVALID(4011, "Token 非法或签名验证失败"),
    TOKEN_EXPIRED(4012, "Token 已过期"),

    FORBIDDEN(4030, "无权限访问该资源"),

    NOT_FOUND(4040, "请求的资源不存在"),

    // ===== 500x 服务端错误 =====
    INTERNAL_ERROR(5000, "服务器内部错误"),
    DB_ERROR(5001, "数据库操作异常"),
    REDIS_ERROR(5002, "缓存操作异常"),
    MQ_ERROR(5003, "消息队列发送失败"),
    REMOTE_CALL_ERROR(5004, "远程服务调用失败（Python 推理端）"),

    // ===== 600x 业务逻辑错误 =====
    USER_NOT_EXIST(6000, "用户不存在"),
    USER_ALREADY_EXIST(6001, "用户名或邮箱已被注册"),
    PASSWORD_ERROR(6002, "密码错误"),
    ACCOUNT_DISABLED(6003, "账号已被禁用"),

    VERIFY_CODE_SEND_FAILED(6004, "验证码发送失败，请稍后重试"),
    VERIFY_CODE_INVALID(6005, "验证码错误或已过期"),
    PHONE_ALREADY_BOUND(6006, "该手机号已被注册"),
    EMAIL_ALREADY_BOUND(6007, "该邮箱已被注册"),
    SMS_SERVICE_UNAVAILABLE(6008, "短信服务暂不可用"),

    SESSION_NOT_EXIST(6010, "会话不存在或已过期"),
    SESSION_STATE_CONFLICT(6011, "会话状态冲突，请勿重复提交"),
    SESSION_ALREADY_FINISHED(6012, "该会话已结束，无法继续交互"),

    INFERENCE_FAILED(6020, "AI 推理失败，请稍后重试"),
    KT_UPDATE_FAILED(6021, "知识点掌握度更新失败"),
    FEATURE_DISABLED(6030, "该功能当前未启用"),
    EXPERIMENT_NOT_ASSIGNED(6040, "当前账号未分配实验"),
    EXPERIMENT_STAGE_LOCKED(6041, "当前实验阶段尚未开放"),
    EXPERIMENT_STATE_CONFLICT(6042, "实验状态冲突，请刷新后重试"),
    EXPERIMENT_ALREADY_SUBMITTED(6043, "该内容已经提交并锁定"),
    EXPERIMENT_DEADLINE_PASSED(6044, "作答时间已结束"),
    EXPERIMENT_ADVANCE_BLOCKED(6045, "仍有学生未完成当前阶段"),
    EXPERIMENT_CONFIG_FROZEN(6046, "实验材料已冻结，不能再修改"),
    EXPERIMENT_ACCOUNT_REQUIRED(6047, "实验模式仅允许预创建账号登录");

    /** HTTP/业务状态码 */
    private final int code;

    /** 状态描述 */
    private final String message;
}
