package com.ai.moderation.dto;

import com.ai.moderation.domain.DetectionJob;
import com.ai.moderation.domain.JobStatus;

import java.time.Instant;

/**
 * 检测任务状态响应。是 {@code GET /jobs/{id}} 的轮询体——前端据此实时展示管线各阶段进度与成败,
 * 对应 {@link DetectionJob} 实体的对外投影。
 *
 * @param id           任务主键
 * @param videoId      关联视频主键
 * @param status       当前阶段({@link JobStatus},如抽音频/转写/匹配/AI 复核/生成建议/完成)
 * @param progress     进度百分比(0~100)
 * @param errorMessage 失败原因(任务失败时填充,否则为 null)
 * @param startedAt    任务开始时间
 * @param completedAt  任务完成时间(未完成时为 null)
 * @param createdAt    任务创建时间
 */
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
    /** 由 {@link DetectionJob} 实体投影为轮询响应。 */
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
