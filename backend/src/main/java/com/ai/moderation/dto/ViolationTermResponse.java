package com.ai.moderation.dto;

import com.ai.moderation.domain.MatchType;
import com.ai.moderation.domain.Severity;
import com.ai.moderation.domain.ViolationTerm;

import java.time.Instant;

/**
 * 违规词的响应体,由 {@link ViolationTerm} 实体投影而来。
 *
 * @param id        违规词主键
 * @param term      违规词本体
 * @param category  所属分类名,可空
 * @param severity  严重级别,见 {@link Severity}
 * @param matchType 召回方式,见 {@link MatchType}
 * @param enabled   启用开关;为 false 时该词不参与规则召回
 * @param variants  变体词,多个变体以约定分隔符拼接为单字符串存储
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
public record ViolationTermResponse(
        Long id,
        String term,
        String category,
        Severity severity,
        MatchType matchType,
        boolean enabled,
        String variants,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * 从违规词实体投影出响应体。
     *
     * @param term 违规词实体
     * @return 对应的响应体
     */
    public static ViolationTermResponse from(ViolationTerm term) {
        return new ViolationTermResponse(
                term.getId(),
                term.getTerm(),
                term.getCategory(),
                term.getSeverity(),
                term.getMatchType(),
                term.isEnabled(),
                term.getVariants(),
                term.getCreatedAt(),
                term.getUpdatedAt()
        );
    }
}

