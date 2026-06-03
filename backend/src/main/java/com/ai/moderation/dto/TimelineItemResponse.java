package com.ai.moderation.dto;

import com.ai.moderation.domain.ReviewStatus;
import com.ai.moderation.domain.Severity;
import com.ai.moderation.domain.TranscriptSource;

public record TimelineItemResponse(
        Long hitId,
        String matchedText,
        String category,
        Severity severity,
        TranscriptSource source,
        ReviewStatus reviewStatus,
        double startTime,
        double endTime,
        String contextText,
        Double aiConfidence,
        String aiReason
) {
}
