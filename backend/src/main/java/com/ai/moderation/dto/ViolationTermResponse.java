package com.ai.moderation.dto;

import com.ai.moderation.domain.MatchType;
import com.ai.moderation.domain.Severity;
import com.ai.moderation.domain.ViolationTerm;

import java.time.Instant;

public record ViolationTermResponse(
        Long id,
        String term,
        String category,
        Severity severity,
        MatchType matchType,
        boolean enabled,
        String variants,
        Instant createdAt,
        Instant updatedAt
) {
    public static ViolationTermResponse from(ViolationTerm term) {
        return new ViolationTermResponse(
                term.getId(),
                term.getTerm(),
                term.getCategory(),
                term.getSeverity(),
                term.getMatchType(),
                term.isEnabled(),
                term.getVariants(),
                term.getCreatedAt(),
                term.getUpdatedAt()
        );
    }
}

