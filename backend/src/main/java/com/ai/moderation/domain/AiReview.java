package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("ai_reviews")
public class AiReview implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long hitId;

    private boolean violation;

    private Double confidence;

    private String category;

    private String reason;

    private String rawResponse;

    private Instant createdAt = Instant.now();
}
