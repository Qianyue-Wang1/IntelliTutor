package com.intellitutor.scut.common.exception;

import com.intellitutor.scut.common.results.ResultCode;
import lombok.Getter;

/**
 * 业务逻辑异常
 *
 * <p>
 * 用于在 Service 层主动抛出预期内的业务错误（如用户不存在、会话状态冲突等）。
 * 由 {@link GlobalExceptionHandler} 统一捕获并转换为 {@code Result} 响应。
 *
 * <p>
 * <b>使用示例：</b>
 * 
 * <pre>{@code
 * if (user == null) {
 *     throw new BusinessException(ResultCode.USER_NOT_EXIST);
 * }
 * // 附加自定义描述
 * throw new BusinessException(ResultCode.SESSION_STATE_CONFLICT,
 *         "当前会话 sessionId=" + sessionId + " 正在推理中，请勿重复提交");
 * }</pre>
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 对应的业务状态码枚举 */
    private final ResultCode resultCode;

    /**
     * 使用 {@link ResultCode} 构造，描述信息取枚举默认值。
     *
     * @param resultCode 业务状态码
     */
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }

    /**
     * 使用 {@link ResultCode} 构造，并附加自定义的详细描述。
     *
     * @param resultCode 业务状态码
     * @param message    详细描述（会覆盖枚举默认 message 展示给前端）
     */
    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    /**
     * 使用 {@link ResultCode} 构造，同时保留原始异常链（便于日志追踪根因）。
     *
     * @param resultCode 业务状态码
     * @param message    详细描述
     * @param cause      原始异常
     */
    public BusinessException(ResultCode resultCode, String message, Throwable cause) {
        super(message, cause);
        this.resultCode = resultCode;
    }
}
