package com.intellitutor.scut.common.results;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * 全局统一 HTTP 响应包装类
 *
 * <p>
 * 所有 Controller 方法均应返回 {@code Result<T>}，前端通过 {@code code} 字段判断业务结果：
 * <ul>
 * <li>{@code code == 200}：成功，{@code data} 中包含业务数据</li>
 * <li>{@code code == 202}：已受理异步任务，{@code data} 中包含 taskId</li>
 * <li>其他：失败，{@code message} 中包含可读错误描述</li>
 * </ul>
 *
 * <p>
 * 泛型参数 {@code T} 约束响应数据类型，序列化时 {@code null} 的 {@code data} 字段被隐藏，
 * 避免前端判断冗余。
 *
 * @param <T> 响应数据类型
 * @author intellitutor-team
 * @since 1.0.0
 */
@Getter
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务状态码，对应 {@link ResultCode#getCode()} */
    private final int code;

    /** 响应描述信息 */
    private final String message;

    /** 响应业务数据，失败时为 null（不序列化） */
    private final T data;

    /** 响应时间戳（Unix epoch 秒） */
    private final long timestamp;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = Instant.now().getEpochSecond();
    }

    // ----------------------------------------------------------------
    // 成功工厂方法
    // ----------------------------------------------------------------

    /**
     * 操作成功，携带数据。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 成功响应体
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /**
     * 操作成功，无需携带数据（如删除、更新类接口）。
     *
     * @return 成功响应体
     */
    public static Result<Void> ok() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    /**
     * 异步任务已受理（HTTP 202 语义），携带 taskId 或占位数据。
     *
     * @param data 任务追踪数据（如 taskId）
     * @param <T>  数据类型
     * @return 已受理响应体
     */
    public static <T> Result<T> accepted(T data) {
        return new Result<>(ResultCode.ACCEPTED.getCode(), ResultCode.ACCEPTED.getMessage(), data);
    }

    // ----------------------------------------------------------------
    // 失败工厂方法
    // ----------------------------------------------------------------

    /**
     * 失败响应，使用 {@link ResultCode} 枚举。
     *
     * @param resultCode 业务状态枚举
     * @param <T>        数据类型（通常为 Void）
     * @return 失败响应体
     */
    public static <T> Result<T> fail(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    /**
     * 失败响应，使用 {@link ResultCode} 枚举并覆盖描述信息（用于附加详细原因）。
     *
     * @param resultCode 业务状态枚举
     * @param message    覆盖的描述信息
     * @param <T>        数据类型
     * @return 失败响应体
     */
    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        return new Result<>(resultCode.getCode(), message, null);
    }

    /**
     * 失败响应，携带自定义错误码与描述（谨慎使用，优先使用枚举）。
     *
     * @param code    自定义状态码
     * @param message 描述信息
     * @param <T>     数据类型
     * @return 失败响应体
     */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    // ----------------------------------------------------------------
    // 工具方法
    // ----------------------------------------------------------------

    /**
     * 判断当前响应是否为成功状态。
     *
     * @return {@code true} 表示 code == 200
     */
    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }
}
