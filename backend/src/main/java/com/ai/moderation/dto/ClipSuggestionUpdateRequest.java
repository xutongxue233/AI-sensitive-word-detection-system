package com.ai.moderation.dto;

import com.ai.moderation.domain.ClipStatus;
import jakarta.validation.constraints.NotNull;

public record ClipSuggestionUpdateRequest(
        Double startTime,
        Double endTime,
        @NotNull ClipStatus status
) {
}

