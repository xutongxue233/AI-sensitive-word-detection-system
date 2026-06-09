package com.ai.moderation.dto;

/**
 * 批量操作中单条记录的执行结果。
 */
public record BatchItemResponse(
        Long id,
        boolean success,
        String message
) {
    public static BatchItemResponse ok(Long id, String message) {
        return new BatchItemResponse(id, true, message);
    }

    public static BatchItemResponse failed(Long id, String message) {
        return new BatchItemResponse(id, false, message);
    }
}
