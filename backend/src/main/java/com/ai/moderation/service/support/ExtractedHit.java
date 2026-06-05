package com.ai.moderation.service.support;

/**
 * AI 整篇提取出的单个命中片段。
 * 注意:{@code segmentSeq} 对应 AI 返回 JSON 中的 {@code sequenceNo} 字段(字幕段号)。
 *
 * @param segmentSeq  命中所在字幕段的段号(对应转写段 sequenceNo)
 * @param matchedText 命中的原文连续子串(用于在段内定位)
 * @param term        命中的词库词
 * @param category    命中分类
 * @param severity    严重级别(字符串形式,后续解析为 Severity 枚举)
 * @param confidence  命中置信度,取值 0~1
 * @param reason      一句中文命中依据
 */
public record ExtractedHit(
        int segmentSeq,
        String matchedText,
        String term,
        String category,
        String severity,
        double confidence,
        String reason
) {
}
