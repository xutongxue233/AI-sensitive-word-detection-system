package com.ai.moderation.dto;

import com.ai.moderation.domain.TermCategory;

import java.time.Instant;

public record TermCategoryResponse(
        Long id,
        String name,
        String description,
        Instant createdAt
) {
    public static TermCategoryResponse from(TermCategory category) {
        return new TermCategoryResponse(category.getId(), category.getName(), category.getDescription(), category.getCreatedAt());
    }
}

