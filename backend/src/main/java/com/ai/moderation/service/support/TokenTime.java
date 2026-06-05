package com.ai.moderation.service.support;

/**
 * 归一化段文本中一个词元(token)的字符区间与对应视频时间戳,
 * 用于把段内字符偏移映射回起止秒。
 *
 * @param normalizedStart 词元在归一化段文本中的起始字符偏移(含)
 * @param normalizedEnd   词元在归一化段文本中的结束字符偏移(不含)
 * @param startTime       词元起始秒
 * @param endTime         词元结束秒
 */
public record TokenTime(
        int normalizedStart,
        int normalizedEnd,
        double startTime,
        double endTime
) {
}
