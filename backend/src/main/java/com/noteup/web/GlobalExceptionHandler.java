package com.noteup.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理，将后端异常统一转换为 {@code {"message": "..."}} JSON。
 *
 * <p>状态码映射：{@link ApiException} 使用其自带状态码；请求体校验失败
 * （{@link MethodArgumentNotValidException}）与 {@link IllegalArgumentException}
 * 返回 400；其余未预期异常记录堆栈后返回 500，并隐藏内部细节。</p>
 *
 * <p>注意：Spring Security 过滤器链中的认证/授权失败不会进入本处理器，
 * 而是由 {@code SecurityConfig} 的 entry point 与 access denied handler
 * 直接写出 401/403 响应。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常，保留异常中声明的 HTTP 状态码。
     *
     * @param e 业务异常
     * @return 对应状态码与错误信息
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("message", e.getMessage()));
    }

    /**
     * 处理 Bean Validation 校验失败，取第一个字段错误的信息返回 400。
     *
     * @param e 请求体校验异常
     * @return 400 与校验提示；无法取得字段信息时使用通用文案
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        FieldError error = e.getBindingResult().getFieldError();
        String message = error == null ? "参数校验失败" : error.getDefaultMessage();
        return ResponseEntity.badRequest().body(Map.of("message", message == null ? "参数校验失败" : message));
    }

    /**
     * 处理服务层抛出的非法参数异常，按 400 返回。
     *
     * @param e 非法参数异常
     * @return 400 与异常信息
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    /**
     * 兜底处理未预期异常，记录日志并返回 500，避免泄露堆栈等内部信息。
     *
     * @param e 未捕获异常
     * @return 500 与通用错误文案
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        log.error("Unhandled request error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "服务器内部错误"));
    }
}