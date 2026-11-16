package com.noteup.web;

import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带希望返回给客户端的 HTTP 状态码。
 *
 * <p>服务层或控制器在发现可预期的业务错误时抛出本异常，由
 * {@link GlobalExceptionHandler#handleApi(ApiException)} 统一转换为
 * {@code {"message": "..."}} 形式的 JSON 响应。相比直接返回
 * {@code ResponseEntity}，这样可以让业务代码与 Web 层解耦。</p>
 */
public class ApiException extends RuntimeException {

    /** 对应的 HTTP 状态码。 */
    private final HttpStatus status;

    /**
     * 构造业务异常。
     *
     * @param status  期望返回的 HTTP 状态码
     * @param message 面向用户的中文错误信息
     */
    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * 获取对应的 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public HttpStatus getStatus() {
        return status;
    }

    /**
     * 构造 400 Bad Request，用于参数缺失、格式错误等客户端问题。
     *
     * @param message 错误信息
     * @return 业务异常实例
     */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 构造 404 Not Found，用于资源不存在或不可见。
     *
     * @param message 错误信息
     * @return 业务异常实例
     */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    /**
     * 构造 409 Conflict，用于与当前资源状态冲突的操作，如用户名重复。
     *
     * @param message 错误信息
     * @return 业务异常实例
     */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    /**
     * 构造 401 Unauthorized，用于未登录或凭证错误。
     *
     * @param message 错误信息
     * @return 业务异常实例
     */
    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    /**
     * 构造 403 Forbidden，用于已识别身份但无权执行的操作。
     *
     * @param message 错误信息
     * @return 业务异常实例
     */
    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    /**
     * 构造 429 Too Many Requests，用于触发限流。
     *
     * @param message 错误信息
     * @return 业务异常实例
     */
    public static ApiException tooMany(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, message);
    }
}