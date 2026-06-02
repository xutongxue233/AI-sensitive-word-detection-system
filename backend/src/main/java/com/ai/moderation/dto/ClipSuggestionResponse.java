package com.ai.moderation.dto;

import com.ai.moderation.domain.ClipStatus;
import com.ai.moderation.domain.ClipSuggestion;

public record ClipSuggestionResponse(
        Long id,
        Long hitId,
        String matchedText,
        double startTime,
        double endTime,
        double paddingSeconds,
        ClipStatus status,
        String exportPath
) {
    public static ClipSuggestionResponse from(ClipSuggestion suggestion, String matchedText) {
        return new ClipSuggestionResponse(
                suggestion.getId(),
                suggestion.getHitId(),
                matchedText,
                suggestion.getStartTime(),
                suggestion.getEndTime(),
                suggestion.getPaddingSeconds(),
                suggestion.getStatus(),
                suggestion.getExportPath()
        );
    }
}
