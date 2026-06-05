package com.ai.moderation.service.support;

/**
 * 视频剪辑用的时间区间(秒),表示一段要保留或删除的视频范围。
 * 由 {@link com.ai.moderation.service.ExportService} 依据已确认的剪辑建议构建,
 * 交 {@link com.ai.moderation.service.FfmpegService} 做片段切割。
 *
 * @param start 起始秒
 * @param end   结束秒
 */
public record VideoTimeRange(double start, double end) {
}
