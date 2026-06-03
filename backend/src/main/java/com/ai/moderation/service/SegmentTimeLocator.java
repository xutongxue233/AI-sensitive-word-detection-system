package com.ai.moderation.service;

import com.ai.moderation.asr.TextNormalizer;
import com.ai.moderation.domain.TranscriptSegment;
import com.ai.moderation.domain.TranscriptWord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 段内时间定位:基于 Whisper 词级时间戳,把段文本里的字符偏移或命中子串映射回视频起止秒。
 * 规则召回与 AI 整篇提取共用——前者按候选词在归一化段文本中的位置定位,
 * 后者按 AI 返回的命中原文子串定位。
 */
@Component
public class SegmentTimeLocator {
    private final TextNormalizer textNormalizer;

    public SegmentTimeLocator(TextNormalizer textNormalizer) {
        this.textNormalizer = textNormalizer;
    }

    public SegmentIndex index(TranscriptSegment segment, List<TranscriptWord> words) {
        return SegmentIndex.from(segment, words);
    }

    /**
     * 按命中原文子串在段内定位起止秒。子串经同源归一化后在段归一化文本里查找,
     * 命中则用词级时间戳映射;找不到则退回段级起止时间(命中不丢,仅时间精度降级)。
     */
    public TimeRange locate(TranscriptSegment segment, List<TranscriptWord> words, String matchedText) {
        SegmentIndex index = index(segment, words);
        String target = textNormalizer.normalizeForMatch(matchedText);
        if (!target.isBlank()) {
            int found = index.normalizedText().indexOf(target);
            if (found >= 0) {
                return index.locate(found, found + target.length());
            }
        }
        return new TimeRange(segment.getStartTime(), segment.getEndTime());
    }

    /**
     * 取目标段及其前后各一段,拼成「[mm:ss] 文本」上下文串,供命中复核参考。
     */
    public String buildContext(List<TranscriptSegment> segments, int index) {
        StringBuilder builder = new StringBuilder();
        for (int i = Math.max(0, index - 1); i <= Math.min(segments.size() - 1, index + 1); i++) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append('[')
                    .append(formatTime(segments.get(i).getStartTime()))
                    .append("] ")
                    .append(segments.get(i).getText());
        }
        return builder.toString();
    }

    private String formatTime(double seconds) {
        long total = (long) seconds;
        long minutes = total / 60;
        long remain = total % 60;
        return "%02d:%02d".formatted(minutes, remain);
    }

    public record TimeRange(double start, double end) {
    }

    public record SegmentIndex(String normalizedText, List<TokenTime> tokenTimes, TranscriptSegment segment) {
        static SegmentIndex from(TranscriptSegment segment, List<TranscriptWord> words) {
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

        public TimeRange locate(int startOffset, int endOffset) {
            List<TokenTime> matched = tokenTimes.stream()
                    .filter(token -> token.normalizedEnd() > startOffset && token.normalizedStart() < endOffset)
                    .toList();
            if (!matched.isEmpty()) {
                return new TimeRange(matched.getFirst().startTime(), matched.getLast().endTime());
            }
            double duration = segment.getEndTime() - segment.getStartTime();
            double startRatio = normalizedText.isBlank() ? 0 : startOffset / (double) normalizedText.length();
            double endRatio = normalizedText.isBlank() ? startRatio : endOffset / (double) normalizedText.length();
            return new TimeRange(
                    segment.getStartTime() + duration * startRatio,
                    segment.getStartTime() + duration * Math.max(endRatio, startRatio)
            );
        }
    }

    public record TokenTime(int normalizedStart, int normalizedEnd, double startTime, double endTime) {
    }
}
