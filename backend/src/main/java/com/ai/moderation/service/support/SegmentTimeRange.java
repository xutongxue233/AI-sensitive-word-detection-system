package com.ai.moderation.service.support;

/**
 * 字幕段内命中的起止时间(秒)。由 {@link com.ai.moderation.service.SegmentTimeLocator}
 * 基于词级时间戳定位命中子串得到;定位失败时退回段级起止时间。
 *
 * @param start 起始秒
 * @param end   结束秒
 */
public record SegmentTimeRange(double start, double end) {
}
