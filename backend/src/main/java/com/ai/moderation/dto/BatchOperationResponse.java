package com.ai.moderation.dto;

import java.util.List;

/**
 * 批量操作聚合响应。
 */
public record BatchOperationResponse(
        int total,
        int succeeded,
        int failed,
        List<BatchItemResponse> items
) {
    public static BatchOperationResponse from(List<BatchItemResponse> items) {
        int succeeded = (int) items.stream().filter(BatchItemResponse::success).count();
        return new BatchOperationResponse(items.size(), succeeded, items.size() - succeeded, items);
    }
}
