package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("transcript_segments")
public class TranscriptSegment implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;

    private int sequenceNo;

    private double startTime;

    private double endTime;

    private String text;
}
