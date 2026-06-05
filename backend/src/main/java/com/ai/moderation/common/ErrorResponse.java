package com.ai.moderation.common;

import java.time.Instant;

/**
 * 统一错误响应的 JSON 契约。后端所有异常出口({@link GlobalExceptionHandler})均以此结构回传,
 * 保证前端拿到一致的错误形态便于解析与展示。
 *
 * @param error     机器可读错误码:业务异常用 {@link org.springframework.http.HttpStatus} 名,
 *                  校验失败固定为 VALIDATION_ERROR,其余兜底为 INTERNAL_ERROR
 * @param message   面向人的可读错误消息
 * @param timestamp 错误发生时刻
 * @param path      触发错误的请求 URI
 */
public record ErrorResponse(
        String error,
        String message,
        Instant timestamp,
        String path
) {
}

