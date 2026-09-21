package com.ai.moderation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 画面硬字幕 OCR 服务的接入参数,绑定 {@code app.subtitle-ocr.*}。
 *
 * <p>OCR 是独立的 PaddleOCR FastAPI 进程(默认 {@code http://127.0.0.1:9001},
 * <b>绝不引入 torch</b>——与 paddle 的 cuDNN 同进程冲突会崩),对应检测管线的画面腿:
 * 按间隔抽帧、仅识别画面底部区域以召回硬字幕。画面腿<b>仅在无外部字幕文件时启用,且失败降级为空、不拖垮音频腿</b>。
 *
 * @param enabled         是否启用画面 OCR 接入
 * @param baseUrl         OCR 服务基址
 * @param ocrPath         OCR 接口相对路径,默认 {@code /ocr-subtitles}
 * @param intervalSeconds 抽帧间隔秒数,默认 1.5;越小越密、越慢
 * @param cropBottomRatio 仅识别画面底部的高度比例(0~1),默认 0.35,因硬字幕多在底部,裁剪可提速并降误检
 * @param minConfidence   OCR 结果最低置信度过滤阈值(0~1),默认 0.65,低于此值的识别结果丢弃
 */
@ConfigurationProperties(prefix = "app.subtitle-ocr")
public record SubtitleOcrProperties(
        boolean enabled,
        String baseUrl,
        String ocrPath,
        double intervalSeconds,
        double cropBottomRatio,
        double minConfidence
) {
    /** 紧凑构造器:对缺省或越界的参数兜底,保证抽帧间隔、底部比例、置信度落在有效区间。 */
    public SubtitleOcrProperties {
        if (ocrPath == null || ocrPath.isBlank()) {
            ocrPath = "/ocr-subtitles";
        }
        if (intervalSeconds <= 0) {
            intervalSeconds = 1.5;
        }
        // 比例须在 (0,1] 内,越界回到默认底部 35%
        if (cropBottomRatio <= 0 || cropBottomRatio > 1) {
            cropBottomRatio = 0.35;
        }
        // 置信度须在 (0,1] 内,越界回到默认 0.65
        if (minConfidence <= 0 || minConfidence > 1) {
            minConfidence = 0.65;
        }
    }
}
