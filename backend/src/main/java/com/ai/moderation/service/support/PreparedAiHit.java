package com.ai.moderation.service.support;

import com.ai.moderation.domain.TermHit;

/**
 * 待入库的 AI 提取命中及其判定理由的临时载体,
 * 在 {@link com.ai.moderation.service.AiExtractionService} 的"提取→去重→落库"链路中传递。
 *
 * @param hit    组装好的命中记录(尚未持久化)
 * @param reason AI 给出的一句中文命中依据
 */
public record PreparedAiHit(TermHit hit, String reason) {
}
