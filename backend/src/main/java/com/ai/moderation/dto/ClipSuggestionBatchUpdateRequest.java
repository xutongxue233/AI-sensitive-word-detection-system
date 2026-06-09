package com.ai.moderation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 批量更新剪辑建议请求。
 */
public record ClipSuggestionBatchUpdateRequest(
        @NotEmpty List<@Valid ClipSuggestionBatchItemRequest> items
) {
}
