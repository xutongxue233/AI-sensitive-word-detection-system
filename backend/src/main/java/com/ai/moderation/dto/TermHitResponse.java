package com.ai.moderation.dto;

import com.ai.moderation.domain.*;

public record TermHitResponse(
        Long id,
        Long segmentId,
        Long termId,
        String matchedText,
        String category,
        Severity severity,
        MatchType ruleSource,
        TranscriptSource source,
        double startTime,
        double endTime,
        String contextText,
        ReviewStatus reviewStatus,
        Double aiConfidence,
        AiReviewResponse aiReview
) {
    public static TermHitResponse from(TermHit hit, AiReview review) {
        return new TermHitResponse(
                hit.getId(),
                hit.getSegmentId(),
                hit.getTermId(),
                hit.getMatchedText(),
                hit.getCategory(),
                hit.getSeverity(),
                hit.getRuleSource(),
                hit.getSource(),
                hit.getStartTime(),
                hit.getEndTime(),
                hit.getContextText(),
                hit.getReviewStatus(),
                hit.getAiConfidence(),
                review == null ? null : AiReviewResponse.from(review)
        );
    }
}
