package com.ai.moderation.dto;

import com.ai.moderation.domain.ExportTask;
import com.ai.moderation.domain.ExportTaskStatus;

import java.time.Instant;

/**
 * 导出任务响应。
 */
public record ExportTaskResponse(
        Long id,
        Long videoId,
        Long jobId,
        ExportTaskStatus status,
        int progress,
        String exportPath,
        Integer removedClipCount,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static ExportTaskResponse from(ExportTask task) {
        return new ExportTaskResponse(
                task.getId(),
                task.getVideoId(),
                task.getJobId(),
                task.getStatus(),
                task.getProgress(),
                task.getExportPath(),
                task.getRemovedClipCount(),
                task.getErrorMessage(),
                task.getStartedAt(),
                task.getCompletedAt(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
