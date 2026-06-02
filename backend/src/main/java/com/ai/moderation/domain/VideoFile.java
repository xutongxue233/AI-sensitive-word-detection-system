package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("videos")
public class VideoFile implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String originalFilename;

    private String storedFilename;

    private String storagePath;

    private String subtitlePath;

    private Double durationSeconds;

    private VideoStatus status = VideoStatus.UPLOADED;

    private Instant createdAt = Instant.now();
}
