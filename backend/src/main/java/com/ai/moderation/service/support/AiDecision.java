package com.ai.moderation.service.support;

/**
 * AI 单条命中复核的结构化决策结果。
 *
 * @param violation   是否判定为命中敏感词库(true 表示命中)
 * @param confidence  对该判断的置信度,取值 0~1
 * @param category    命中的敏感分类(可能回退为调用方传入的兜底分类)
 * @param reason      一句中文判定依据
 * @param rawResponse AI 返回的原始内容,留存以便排查
 */
public record AiDecision(
        boolean violation,
        double confidence,
        String category,
        String reason,
        String rawResponse
) {
}
