package com.ai.moderation.dto;

import com.ai.moderation.domain.ClipStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 剪辑建议的更新请求。供审核员在工作台上人工微调单条建议:既可调整时间轴区间,也可改变其确认状态。
 *
 * @param startTime 新的区间起点(秒);为 null 表示不改动时间轴,仅更新状态
 * @param endTime   新的区间终点(秒);为 null 表示不改动时间轴,仅更新状态
 * @param status    目标状态({@link ClipStatus}),必填
 */
public record ClipSuggestionUpdateRequest(
        Double startTime,
        Double endTime,
        @NotNull ClipStatus status
) {
}

