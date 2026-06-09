package com.ai.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 人工从转写句段中选词创建剪辑建议的请求。
 *
 * @param segmentId   选中的转写段 ID
 * @param matchedText 人工选中的文本
 * @param startTime   选中文本在视频时间轴上的起点(秒)
 * @param endTime     选中文本在视频时间轴上的终点(秒)
 */
public record ManualClipSuggestionRequest(
        @NotNull Long segmentId,
        @NotBlank @Size(max = 200) String matchedText,
        @NotNull Double startTime,
        @NotNull Double endTime
) {
}
