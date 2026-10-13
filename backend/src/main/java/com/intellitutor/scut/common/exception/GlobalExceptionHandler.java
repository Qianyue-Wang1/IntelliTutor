package com.intellitutor.scut.common.exception;

import com.intellitutor.scut.common.results.Result;
import com.intellitutor.scut.common.results.ResultCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局统一异常处理器
 *
 * <p>
 * 拦截并处理以下异常类型，统一转换为 {@link Result} 格式响应，避免原始异常栈信息泄露：
 * <ol>
 * <li>{@link BusinessException}：主动抛出的业务逻辑异常</li>
 * <li>{@link MethodArgumentNotValidException}：{@code @RequestBody} JSR-303
 * 校验失败</li>
 * <li>{@link ConstraintViolationException}：{@code @RequestParam / @PathVariable}
 * 校验失败</li>
 * <li>{@link MissingServletRequestParameterException}：缺少必填请求参数</li>
 * <li>{@link MethodArgumentTypeMismatchException}：参数类型不匹配</li>
 * <li>{@link HttpMessageNotReadableException}：请求体无法解析（JSON 格式错误）</li>
 * <li>{@link Exception}：兜底处理，记录完整堆栈到日志</li>
 * </ol>
 *
 * @author intellitutor-team
 * @since 1.0.0
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ----------------------------------------------------------------
    // 1. 业务异常 —— 已预期，INFO 日志即可
    // ----------------------------------------------------------------

    /**
     * 处理业务逻辑异常 {@link BusinessException}。
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK) // HTTP 层统一 200，由业务 code 区分
    public Result<Void> handleBusinessException(BusinessException ex) {
        log.info("[BusinessException] code={}, message={}", ex.getResultCode().getCode(), ex.getMessage());
        return Result.fail(ex.getResultCode(), ex.getMessage());
    }

    // ----------------------------------------------------------------
    // 2. 参数校验异常
    // ----------------------------------------------------------------

    /**
     * 处理 {@code @RequestBody @Valid} 校验失败（JSR-303 Bean Validation）。
     *
     * <p>
     * 将所有字段错误拼接为 "field: message; ..." 格式返回给前端，便于表单定位。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("[ParamValidation] {}", detail);
        return Result.fail(ResultCode.PARAM_ERROR, detail);
    }

    /**
     * 处理 {@code @RequestParam / @PathVariable @Valid} 约束校验失败。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("[ConstraintViolation] {}", detail);
        return Result.fail(ResultCode.PARAM_ERROR, detail);
    }

    /**
     * 处理 {@code @RequestParam} 必填参数缺失。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException ex) {
        String detail = "缺少必填参数: " + ex.getParameterName();
        log.warn("[MissingParam] {}", detail);
        return Result.fail(ResultCode.PARAM_MISSING, detail);
    }

    /**
     * 处理路径变量或请求参数类型不匹配（如 String 传入 Long 字段）。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String detail = String.format("参数 [%s] 类型不匹配，期望类型: %s",
                ex.getName(),
                ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown");
        log.warn("[TypeMismatch] {}", detail);
        return Result.fail(ResultCode.PARAM_TYPE_ERROR, detail);
    }

    /**
     * 处理请求体 JSON 格式错误（无法反序列化）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("[RequestBodyParse] 请求体解析失败: {}", ex.getMessage());
        return Result.fail(ResultCode.PARAM_ERROR, "请求体 JSON 格式错误或字段类型不匹配");
    }

    // ----------------------------------------------------------------
    // 3. 兜底异常 —— 未预期，ERROR 级别并打印完整堆栈
    // ----------------------------------------------------------------

    /**
     * 兜底处理所有未被显式捕获的异常。
     *
     * <p>
     * <b>安全注意：</b>不向前端暴露原始异常信息，防止信息泄露（OWASP A05）。
     * 仅记录完整堆栈到日志，由运维/开发通过日志系统排查。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleUnknownException(Exception ex) {
        log.error("[UnhandledException] 未处理的系统异常", ex);
        return Result.fail(ResultCode.INTERNAL_ERROR);
    }
}
