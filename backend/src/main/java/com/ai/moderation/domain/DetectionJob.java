package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 检测任务状态实体,对应表 {@code detection_jobs}。
 * <p>管线异步执行时逐阶段写入 {@code status}/{@code progress},前端通过 {@code GET /jobs/{id}} 轮询展示进度;
 * 一个任务对应一个上传的 {@link VideoFile}。
 */
@Getter
@Setter
@TableName("detection_jobs")
public class DetectionJob implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 外键,被检测的视频 {@link VideoFile#getId()}。 */
    private Long videoId;

    /** 任务当前阶段,取值见 {@link JobStatus};由管线各阶段推进。 */
    private JobStatus status = JobStatus.QUEUED;

    /** 进度百分比(0~100),供前端轮询展示。 */
    private int progress = 0;

    /** 任务失败时的错误信息,成功则为空。 */
    private String errorMessage;

    private Instant startedAt;

    private Instant completedAt;

    private Instant createdAt = Instant.now();
}
