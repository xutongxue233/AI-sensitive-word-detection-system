package com.ai.moderation.asr;

import java.util.List;

/**
 * 转写管线统一交换类型:一次转写/解析产出的字幕段集合。
 *
 * <p>音频腿(Whisper)、画面腿(OCR)、外部字幕腿(SRT/VTT)均产出此类型,再由
 * {@link TranscriptionMerger} 跨源去重合并为最终结果。
 *
 * @param segments 字幕段列表(可能为空,表示该腿无产出)
 */
public record TranscriptionResult(List<TranscriptionSegment> segments) {
}

