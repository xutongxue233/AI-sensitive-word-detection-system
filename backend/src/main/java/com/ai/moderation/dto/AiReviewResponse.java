package com.ai.moderation.dto;

import com.ai.moderation.domain.AiReview;

public record AiReviewResponse(
        Long id,
        boolean violation,
        Double confidence,
        String category,
        String reason
) {
    public static AiReviewResponse from(AiReview review) {
        return new AiReviewResponse(
                review.getId(),
                review.isViolation(),
                review.getConfidence(),
                review.getCategory(),
                review.getReason()
        );
    }
}

