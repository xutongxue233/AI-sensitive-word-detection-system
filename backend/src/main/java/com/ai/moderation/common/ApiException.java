package com.ai.moderation.common;

import org.springframework.http.HttpStatus;

/**
 * 业务层主动抛出的受控异常,用于表达"可预期的失败"(如资源不存在、参数非法、状态冲突),
 * 区别于未捕获的系统异常。携带期望回传给前端的 HTTP 状态码与可读消息,
 * 最终由 {@link GlobalExceptionHandler} 统一捕获并转成 {@link ErrorResponse} JSON。
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    /**
     * @param status  期望回传给前端的 HTTP 状态码;其名称同时作为 {@link ErrorResponse#error()} 机器可读错误码
     * @param message 面向调用方的可读错误消息
     */
    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}

