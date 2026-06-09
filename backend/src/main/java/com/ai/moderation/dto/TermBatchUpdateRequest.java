package com.ai.moderation.dto;

import com.ai.moderation.domain.MatchType;
import com.ai.moderation.domain.Severity;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 违规词批量更新请求。字段为空表示该字段不变。
 */
public record TermBatchUpdateRequest(
        @NotEmpty List<Long> ids,
        Boolean enabled,
        String category,
        Severity severity,
        MatchType matchType
) {
}
