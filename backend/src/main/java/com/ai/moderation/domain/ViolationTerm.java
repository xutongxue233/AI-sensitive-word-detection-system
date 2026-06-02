package com.ai.moderation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("violation_terms")
public class ViolationTerm implements Identifiable {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String term;

    private String category;

    private Severity severity = Severity.MEDIUM;

    private MatchType matchType = MatchType.EXACT;

    private boolean enabled = true;

    private String variants;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();
}
