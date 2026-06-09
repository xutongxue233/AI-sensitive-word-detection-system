package com.ai.moderation.dto;

import com.ai.moderation.domain.ClipStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 批量更新剪辑建议中的单条更新项。
 */
public record ClipSuggestionBatchItemRequest(
        @NotNull Long id,
        Double startTime,
        Double endTime,
        @NotNull ClipStatus status
) {
    public ClipSuggestionUpdateRequest toUpdateRequest() {
        return new ClipSuggestionUpdateRequest(startTime, endTime, status);
    }
}
