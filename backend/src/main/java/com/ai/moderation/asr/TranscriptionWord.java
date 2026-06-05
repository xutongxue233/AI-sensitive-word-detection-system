package com.ai.moderation.asr;

/**
 * 词级时间戳:转写结果中单个词及其在视频中的起止时刻(秒)。
 *
 * <p>是命中定位到时间轴的最小粒度——音频腿由 Whisper 直接给出,外部字幕腿由
 * {@link SubtitleParser} 按 token 均分段时长伪造。
 *
 * @param word  词文本
 * @param start 词起始时刻(秒,相对视频开头)
 * @param end   词结束时刻(秒,相对视频开头)
 */
public record TranscriptionWord(
        String word,
        double start,
        double end
) {
}

