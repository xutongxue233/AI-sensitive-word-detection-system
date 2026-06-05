package com.ai.moderation.dto;

import com.ai.moderation.domain.MatchType;
import com.ai.moderation.domain.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 违规词的新建/更新请求。
 *
 * @param term      违规词本体,必填
 * @param category  所属分类名,可空
 * @param severity  严重级别,必填,见 {@link Severity}
 * @param matchType 召回方式(决定如何在转写中匹配此词),必填,见 {@link MatchType}
 * @param enabled   启用开关;为 false 时该词不参与规则召回
 * @param variants  变体词,多个变体以约定分隔符拼接为单字符串存储(召回时一并匹配)
 */
public record ViolationTermRequest(
        @NotBlank String term,
        String category,
        @NotNull Severity severity,
        @NotNull MatchType matchType,
        boolean enabled,
        String variants
) {
}

