package com.ai.moderation.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.time.Instant;

/**
 * 后端错误出口中枢。以 {@code @RestControllerAdvice} 全局拦截控制器抛出的异常,统一转为
 * {@link ErrorResponse} JSON,避免每个控制器各自处理错误。覆盖四类异常:
 * <ul>
 *   <li>{@link AsyncRequestNotUsableException}:客户端在异步请求未完成时断连,静默处理;</li>
 *   <li>{@link ApiException}:业务层主动抛出的受控异常,沿用其携带的 HTTP 状态码;</li>
 *   <li>参数校验失败({@link MethodArgumentNotValidException} / {@link jakarta.validation.ConstraintViolationException}),映射为 400;</li>
 *   <li>其余未捕获异常,兜底映射为 500。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    /**
     * 客户端在异步请求尚未完成时主动断连,此时响应已不可写。空方法体是刻意为之:
     * 静默返回 204,避免对已断开的连接再写响应而抛出无意义的二次异常与日志噪声。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void handleClientDisconnect() {
    }

    /** 业务受控异常:沿用 {@link ApiException} 自带的 HTTP 状态码,错误码取该状态码名称。 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(ex.status().name(), ex.getMessage(), Instant.now(), request.getRequestURI()));
    }

    /** 参数/约束校验失败:统一映射为 400,错误码固定为 VALIDATION_ERROR。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<ErrorResponse> handleValidation(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse("VALIDATION_ERROR", ex.getMessage(), Instant.now(), request.getRequestURI()));
    }

    /** 兜底:任何未被上面命中的异常映射为 500,错误码固定为 INTERNAL_ERROR。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse("INTERNAL_ERROR", ex.getMessage(), Instant.now(), request.getRequestURI()));
    }
}
