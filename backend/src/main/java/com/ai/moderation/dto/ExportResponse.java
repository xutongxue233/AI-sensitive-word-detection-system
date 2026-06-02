package com.ai.moderation.dto;

public record ExportResponse(
        Long videoId,
        Long jobId,
        String exportPath,
        int removedClipCount
) {
}

