package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("term_hits")
public class TermHit implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;

    private Long termId;

    private Long segmentId;

    private String matchedText;

    private String category;

    private Severity severity;

    private MatchType ruleSource;

    private double startTime;

    private double endTime;

    private String contextText;

    private ReviewStatus reviewStatus = ReviewStatus.PENDING;

    private Double aiConfidence;

    private Instant createdAt = Instant.now();
}
