package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("transcript_words")
public class TranscriptWord implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;

    private Long segmentId;

    private int sequenceNo;

    private String word;

    private String normalizedWord;

    private double startTime;

    private double endTime;
}
