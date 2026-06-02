package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("detection_jobs")
public class DetectionJob implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long videoId;

    private JobStatus status = JobStatus.QUEUED;

    private int progress = 0;

    private String errorMessage;

    private Instant startedAt;

    private Instant completedAt;

    private Instant createdAt = Instant.now();
}
