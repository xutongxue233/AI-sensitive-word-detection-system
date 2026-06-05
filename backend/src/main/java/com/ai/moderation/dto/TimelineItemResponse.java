package com.ai.moderation.dto;

import com.ai.moderation.domain.ReviewStatus;
import com.ai.moderation.domain.Severity;
import com.ai.moderation.domain.TranscriptSource;

/**
 * 时间轴项:一条已定位到时间区间的命中,供前端在时间轴上渲染与定位。
 *
 * <p>相比 {@link TermHitResponse} 更精简,只保留时间轴展示所需字段。
 *
 * @param hitId        对应命中 id
 * @param matchedText  实际命中的文本片段
 * @param category     命中词所属分类
 * @param severity     严重级别,见 {@link Severity}
 * @param source       命中来源(音频转写 / 画面硬字幕),见 {@link TranscriptSource}
 * @param reviewStatus 复核状态,见 {@link ReviewStatus}
 * @param startTime    起始时间(秒)
 * @param endTime      结束时间(秒)
 * @param contextText  命中所在的上下文文本
 * @param aiConfidence AI 复核置信度;未经 AI 复核时可空
 * @param aiReason     AI 复核给出的理由;未经 AI 复核时可空
 */
public record TimelineItemResponse(
        Long hitId,
        String matchedText,
        String category,
        Severity severity,
        TranscriptSource source,
        ReviewStatus reviewStatus,
        double startTime,
        double endTime,
        String contextText,
        Double aiConfidence,
        String aiReason
) {
}
