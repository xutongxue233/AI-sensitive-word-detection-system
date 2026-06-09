package com.ai.moderation.domain;

/**
 * 导出任务状态。导出任务独立于检测任务,用于异步排队执行 FFmpeg 导出。
 */
public enum ExportTaskStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
}
