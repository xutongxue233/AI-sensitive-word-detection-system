package com.ai.moderation.dto;

import com.ai.moderation.domain.DetectionJob;
import com.ai.moderation.domain.JobStatus;

import java.time.Instant;

public record JobResponse(
        Long id,
        Long videoId,
        JobStatus status,
        int progress,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt
) {
    public static JobResponse from(DetectionJob job) {
        return new JobResponse(
                job.getId(),
                job.getVideoId(),
                job.getStatus(),
                job.getProgress(),
                job.getErrorMessage(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getCreatedAt()
        );
    }
}
