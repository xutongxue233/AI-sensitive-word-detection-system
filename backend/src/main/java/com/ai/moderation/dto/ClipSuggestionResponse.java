package com.ai.moderation.dto;

import com.ai.moderation.domain.ClipStatus;
import com.ai.moderation.domain.ClipSuggestion;
import com.ai.moderation.domain.TranscriptSource;

public record ClipSuggestionResponse(
        Long id,
        Long hitId,
        String matchedText,
        TranscriptSource source,
        String action,
        double startTime,
        double endTime,
        double paddingSeconds,
        ClipStatus status,
        String exportPath,
        Double aiConfidence
) {
    public static ClipSuggestionResponse from(ClipSuggestion suggestion, String matchedText,
                                              TranscriptSource source, Double aiConfidence) {
        return new ClipSuggestionResponse(
                suggestion.getId(),
                suggestion.getHitId(),
                matchedText,
                source,
                source == TranscriptSource.VIDEO_SUBTITLE || source == TranscriptSource.SUBTITLE_FILE
                        ? "BLUR_SUBTITLE"
                        : "REMOVE_AUDIO_SEGMENT",
                suggestion.getStartTime(),
                suggestion.getEndTime(),
                suggestion.getPaddingSeconds(),
                suggestion.getStatus(),
                suggestion.getExportPath(),
                aiConfidence
        );
    }
}
