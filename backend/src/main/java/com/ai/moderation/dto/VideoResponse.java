package com.ai.moderation.dto;

import com.ai.moderation.domain.VideoFile;
import com.ai.moderation.domain.VideoStatus;

import java.time.Instant;

public record VideoResponse(
        Long id,
        String originalFilename,
        String storedFilename,
        String storagePath,
        String subtitlePath,
        Double durationSeconds,
        Long sizeBytes,
        String contentType,
        Integer width,
        Integer height,
        VideoStatus status,
        Instant createdAt
) {
    public static VideoResponse from(VideoFile video) {
        return new VideoResponse(
                video.getId(),
                video.getOriginalFilename(),
                video.getStoredFilename(),
                video.getStoragePath(),
                video.getSubtitlePath(),
                video.getDurationSeconds(),
                video.getSizeBytes(),
                video.getContentType(),
                video.getWidth(),
                video.getHeight(),
                video.getStatus(),
                video.getCreatedAt()
        );
    }
}

