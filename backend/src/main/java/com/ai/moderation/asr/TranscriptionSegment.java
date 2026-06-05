package com.ai.moderation.asr;

import com.ai.moderation.domain.TranscriptSource;

import java.util.List;

/**
 * 转写管线统一交换类型:一个字幕段(一句/一行)及其词级时间戳与来源。
 *
 * <p>{@code source} 决定后续导出方式(音频命中删片段、画面字幕命中去字幕);
 * {@code bbox*} 为画面字幕在帧内的归一化包围盒(0~1),仅 OCR 段携带,用于 delogo 去字幕定位。
 *
 * @param start      段起始时刻(秒)
 * @param end        段结束时刻(秒)
 * @param text       段文本
 * @param words      词级时间戳列表
 * @param source     转写来源,区分音频/画面字幕/外部字幕,直接影响导出处理(见 {@link TranscriptSource})
 * @param bboxX      字幕包围盒左上角 X(归一化 0~1),仅画面 OCR 段非空
 * @param bboxY      字幕包围盒左上角 Y(归一化 0~1),仅画面 OCR 段非空
 * @param bboxWidth  字幕包围盒宽度(归一化 0~1),仅画面 OCR 段非空
 * @param bboxHeight 字幕包围盒高度(归一化 0~1),仅画面 OCR 段非空
 */
public record TranscriptionSegment(
        double start,
        double end,
        String text,
        List<TranscriptionWord> words,
        TranscriptSource source,
        Double bboxX,
        Double bboxY,
        Double bboxWidth,
        Double bboxHeight
) {
    /**
     * 便捷构造器:仅给定时间/文本/词时间戳时,来源默认为 {@link TranscriptSource#AUDIO}、无包围盒。
     */
    public TranscriptionSegment(double start, double end, String text, List<TranscriptionWord> words) {
        this(start, end, text, words, TranscriptSource.AUDIO, null, null, null, null);
    }

    /**
     * 返回仅替换 {@code source} 的不可变副本,其余字段(含 bbox)原样保留。
     *
     * @param source 新的转写来源
     * @return 改写来源后的新段实例
     */
    public TranscriptionSegment withSource(TranscriptSource source) {
        return new TranscriptionSegment(start, end, text, words, source, bboxX, bboxY, bboxWidth, bboxHeight);
    }
}
