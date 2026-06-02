package com.ai.moderation.dto;

import jakarta.validation.constraints.NotBlank;

public record TermCategoryRequest(
        @NotBlank String name,
        String description
) {
}

