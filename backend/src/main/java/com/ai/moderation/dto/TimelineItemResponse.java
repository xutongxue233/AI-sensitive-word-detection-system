package com.ai.moderation.dto;

import com.ai.moderation.domain.ReviewStatus;
import com.ai.moderation.domain.Severity;

public record TimelineItemResponse(
        Long hitId,
        String matchedText,
        String category,
        Severity severity,
        ReviewStatus reviewStatus,
        double startTime,
        double endTime,
        String contextText
) {
}

