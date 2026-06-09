package com.ai.moderation.dto;

import com.ai.moderation.domain.ClipStatus;
import com.ai.moderation.domain.ClipSuggestion;
import com.ai.moderation.domain.TranscriptSource;

/**
 * 剪辑/去字幕建议的对外响应。对应 {@link ClipSuggestion} 实体的投影,是审核工作台时间轴上每条可
 * 确认/可调整/可导出建议的载体。其中 {@code action} 不来自实体存储,而是由 {@code from} 工厂依据
 * 命中来源({@link TranscriptSource})实时推导——这是本响应的核心业务规则,决定导出时走删音频段
 * 还是去字幕两条不同路径(见 {@code from})。
 *
 * @param id             建议主键
 * @param hitId          关联的命中记录主键({@link com.ai.moderation.domain.TermHit})
 * @param matchedText    命中的原文文本
 * @param source         命中来源(音频/画面硬字幕/外部字幕文件),决定 action 分流
 * @param action         处置动作:BLUR_SUBTITLE(去字幕)或 REMOVE_AUDIO_SEGMENT(删音频段),由 source 推导
 * @param startTime      建议处置区间起点(秒)
 * @param endTime        建议处置区间终点(秒)
 * @param segmentStartTime 命中所在字幕/转写句段起点(秒);缺失时为 null,用于前端一键扩展到整句
 * @param segmentEndTime   命中所在字幕/转写句段终点(秒);缺失时为 null,用于前端一键扩展到整句
 * @param paddingSeconds 区间前后预留的留白(秒),避免裁切过紧导致内容突兀
 * @param status         建议状态({@link ClipStatus},如待确认/已确认/已忽略)
 * @param exportPath     导出产物路径(尚未导出时为 null)
 * @param aiConfidence   触发该建议的命中所对应 AI 复核置信度(0~1)
 */
public record ClipSuggestionResponse(
        Long id,
        Long hitId,
        String matchedText,
        TranscriptSource source,
        String action,
        double startTime,
        double endTime,
        Double segmentStartTime,
        Double segmentEndTime,
        double paddingSeconds,
        ClipStatus status,
        String exportPath,
        Double aiConfidence
) {
    /**
     * 由 {@link ClipSuggestion} 实体及其上下文投影为响应。
     * 关键业务规则:画面硬字幕({@link TranscriptSource#VIDEO_SUBTITLE})或外部字幕文件
     * ({@link TranscriptSource#SUBTITLE_FILE})命中走 BLUR_SUBTITLE(画面去字幕),
     * 其余(音频命中)走 REMOVE_AUDIO_SEGMENT(删除音频时间片段)。
     */
    public static ClipSuggestionResponse from(ClipSuggestion suggestion, String matchedText,
                                              TranscriptSource source, Double aiConfidence,
                                              Double segmentStartTime, Double segmentEndTime) {
        return new ClipSuggestionResponse(
                suggestion.getId(),
                suggestion.getHitId(),
                matchedText,
                source,
                // 来源决定处置动作:字幕类命中去字幕,其余删音频段
                source == TranscriptSource.VIDEO_SUBTITLE || source == TranscriptSource.SUBTITLE_FILE
                        ? "BLUR_SUBTITLE"
                        : "REMOVE_AUDIO_SEGMENT",
                suggestion.getStartTime(),
                suggestion.getEndTime(),
                segmentStartTime,
                segmentEndTime,
                suggestion.getPaddingSeconds(),
                suggestion.getStatus(),
                suggestion.getExportPath(),
                aiConfidence
        );
    }
}
