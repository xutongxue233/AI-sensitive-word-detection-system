package com.ai.moderation.dto;

import com.ai.moderation.domain.AiReview;
import com.ai.moderation.domain.MatchType;
import com.ai.moderation.domain.ReviewStatus;
import com.ai.moderation.domain.Severity;
import com.ai.moderation.domain.TermHit;
import com.ai.moderation.domain.TranscriptSource;

/**
 * 单条命中(违规词候选)的响应体,聚合命中本身与其 AI 复核结论。
 *
 * <p>由规则召回或 AI 整篇提取产生,经置信度把关后决定是否进入时间轴并生成剪辑建议。
 * {@code source} 决定导出策略:{@link TranscriptSource#AUDIO} 走删片段、
 * {@link TranscriptSource#VIDEO_SUBTITLE} 走 delogo 去字幕。
 *
 * @param id           命中主键
 * @param segmentId    所属转写段 id
 * @param termId       命中的违规词 id
 * @param matchedText  实际命中的文本片段
 * @param category     命中词所属分类
 * @param severity     严重级别,见 {@link Severity}
 * @param ruleSource   规则召回方式(命中由哪类匹配产生),见 {@link MatchType}
 * @param source       命中来源(音频转写 / 画面硬字幕),决定导出处理方式,见 {@link TranscriptSource}
 * @param startTime    命中起始时间(秒)
 * @param endTime      命中结束时间(秒)
 * @param contextText  命中所在的上下文文本,供人工复核参考
 * @param reviewStatus 复核状态,见 {@link ReviewStatus}
 * @param aiConfidence AI 复核置信度;未经 AI 复核时为 null
 * @param aiReview     AI 复核详情;未经 AI 复核时为 null
 */
public record TermHitResponse(
        Long id,
        Long segmentId,
        Long termId,
        String matchedText,
        String category,
        Severity severity,
        MatchType ruleSource,
        TranscriptSource source,
        double startTime,
        double endTime,
        String contextText,
        ReviewStatus reviewStatus,
        Double aiConfidence,
        AiReviewResponse aiReview
) {
    /**
     * 从命中实体与其 AI 复核记录投影出响应体。
     *
     * @param hit    命中实体
     * @param review 对应的 AI 复核记录;为 null 时表示未经 AI 复核,{@code aiReview} 字段置空
     * @return 对应的响应体
     */
    public static TermHitResponse from(TermHit hit, AiReview review) {
        return new TermHitResponse(
                hit.getId(),
                hit.getSegmentId(),
                hit.getTermId(),
                hit.getMatchedText(),
                hit.getCategory(),
                hit.getSeverity(),
                hit.getRuleSource(),
                hit.getSource(),
                hit.getStartTime(),
                hit.getEndTime(),
                hit.getContextText(),
                hit.getReviewStatus(),
                hit.getAiConfidence(),
                review == null ? null : AiReviewResponse.from(review)
        );
    }
}
