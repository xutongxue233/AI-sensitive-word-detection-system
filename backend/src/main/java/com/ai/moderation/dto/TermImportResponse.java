package com.ai.moderation.dto;

public record TermImportResponse(
        int importedCount,
        int skippedCount
) {
}

