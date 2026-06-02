package com.ai.moderation.dto;

import com.ai.moderation.domain.MatchType;
import com.ai.moderation.domain.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ViolationTermRequest(
        @NotBlank String term,
        String category,
        @NotNull Severity severity,
        @NotNull MatchType matchType,
        boolean enabled,
        String variants
) {
}

