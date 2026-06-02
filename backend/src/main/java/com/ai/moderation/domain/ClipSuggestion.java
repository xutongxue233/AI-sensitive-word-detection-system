package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("clip_suggestions")
public class ClipSuggestion implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;

    private Long hitId;

    private double startTime;

    private double endTime;

    private double paddingSeconds = 1.0;

    private ClipStatus status = ClipStatus.PENDING;

    private String exportPath;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();
}
