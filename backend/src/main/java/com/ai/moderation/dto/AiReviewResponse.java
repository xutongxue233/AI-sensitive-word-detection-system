package com.ai.moderation.dto;

import com.ai.moderation.domain.AiReview;

/**
 * 单条命中的 AI 上下文复核结论。对应 {@link AiReview} 实体的对外投影,
 * 携带模型给出的违规判定、置信度与归因,供前端在时间轴上展示「为什么命中」。
 *
 * @param id         复核记录主键
 * @param violation  模型是否判定为违规
 * @param confidence 置信度(0~1);仅当 violation 为真且达到阈值才会进时间轴并生成剪辑
 * @param category   违规归类
 * @param reason     判定理由(可解释、可审计)
 */
public record AiReviewResponse(
        Long id,
        boolean violation,
        Double confidence,
        String category,
        String reason
) {
    /** 由 {@link AiReview} 实体投影为对外响应。 */
    public static AiReviewResponse from(AiReview review) {
        return new AiReviewResponse(
                review.getId(),
                review.isViolation(),
                review.getConfidence(),
                review.getCategory(),
                review.getReason()
        );
    }
}

