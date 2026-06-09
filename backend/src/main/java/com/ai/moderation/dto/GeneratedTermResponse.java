package com.ai.moderation.dto;

import com.ai.moderation.domain.MatchType;
import com.ai.moderation.domain.Severity;

/**
 * AI 生成的词库候选项。该响应不会直接写库,需前端人工确认后再调用新增词条接口。
 *
 * @param term      词条本体
 * @param category  建议分类
 * @param severity  建议严重级别
 * @param matchType 建议匹配方式
 * @param variants  建议变体词
 * @param reason    生成依据
 */
public record GeneratedTermResponse(
        String term,
        String category,
        Severity severity,
        MatchType matchType,
        String variants,
        String reason
) {
}
