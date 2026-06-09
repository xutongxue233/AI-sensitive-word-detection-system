package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 视频导出队列任务,对应表 {@code export_tasks}。
 */
@Getter
@Setter
@TableName("export_tasks")
public class ExportTask implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long videoId;

    private Long jobId;

    private ExportTaskStatus status = ExportTaskStatus.QUEUED;

    private int progress = 0;

    private String exportPath;

    private Integer removedClipCount;

    private String errorMessage;

    private Instant startedAt;

    private Instant completedAt;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();
}
