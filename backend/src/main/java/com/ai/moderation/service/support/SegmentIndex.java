package com.ai.moderation.service.support;

import com.ai.moderation.domain.TranscriptSegment;
import com.ai.moderation.domain.TranscriptWord;

import java.util.ArrayList;
import java.util.List;

/**
 * 字幕段的归一化索引:把段内各词的归一化文本顺序拼接,并记录每个词元的字符区间与时间戳,
 * 从而支持按字符偏移区间反查命中的起止秒。
 *
 * @param normalizedText 段内各词归一化文本顺序拼接后的串
 * @param tokenTimes     每个词元的字符区间与时间戳
 * @param segment        原始字幕段(用于回退到段级时间与按时长比例估算)
 */
public record SegmentIndex(
        String normalizedText,
        List<TokenTime> tokenTimes,
        TranscriptSegment segment
) {
    /**
     * 从字幕段与其词级时间戳构建索引。归一化文本为空的词被跳过,不参与定位。
     *
     * @param segment 字幕段
     * @param words   该段的词级时间戳列表(按段内顺序)
     * @return 构建好的段索引
     */
    public static SegmentIndex from(TranscriptSegment segment, List<TranscriptWord> words) {
        StringBuilder normalizedText = new StringBuilder();
        List<TokenTime> tokenTimes = new ArrayList<>();
        for (TranscriptWord word : words) {
            if (word.getNormalizedWord() == null || word.getNormalizedWord().isBlank()) {
                continue;
            }
            int start = normalizedText.length();
            normalizedText.append(word.getNormalizedWord());
            tokenTimes.add(new TokenTime(start, normalizedText.length(), word.getStartTime(), word.getEndTime()));
        }
        return new SegmentIndex(normalizedText.toString(), tokenTimes, segment);
    }

    /**
     * 按归一化文本中的字符区间 [startOffset, endOffset) 定位起止秒。
     * 区间覆盖到的词元有时间戳时取其首尾时间;否则按字符占比在段时长内线性估算。
     *
     * @param startOffset 起始字符偏移(含)
     * @param endOffset   结束字符偏移(不含)
     * @return 命中的起止时间
     */
    public SegmentTimeRange locate(int startOffset, int endOffset) {
        List<TokenTime> matched = tokenTimes.stream()
                .filter(token -> token.normalizedEnd() > startOffset && token.normalizedStart() < endOffset)
                .toList();
        if (!matched.isEmpty()) {
            return new SegmentTimeRange(matched.getFirst().startTime(), matched.getLast().endTime());
        }
        double duration = segment.getEndTime() - segment.getStartTime();
        double startRatio = normalizedText.isBlank() ? 0 : startOffset / (double) normalizedText.length();
        double endRatio = normalizedText.isBlank() ? startRatio : endOffset / (double) normalizedText.length();
        return new SegmentTimeRange(
                segment.getStartTime() + duration * startRatio,
                segment.getStartTime() + duration * Math.max(endRatio, startRatio)
        );
    }
}
