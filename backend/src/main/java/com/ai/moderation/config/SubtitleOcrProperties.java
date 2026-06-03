package com.ai.moderation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.subtitle-ocr")
public record SubtitleOcrProperties(
        boolean enabled,
        String ocrPath,
        double intervalSeconds,
        double cropBottomRatio,
        double minConfidence
) {
    public SubtitleOcrProperties {
        if (ocrPath == null || ocrPath.isBlank()) {
            ocrPath = "/ocr-subtitles";
        }
        if (intervalSeconds <= 0) {
            intervalSeconds = 0.75;
        }
        if (cropBottomRatio <= 0 || cropBottomRatio > 1) {
            cropBottomRatio = 0.35;
        }
        if (minConfidence <= 0 || minConfidence > 1) {
            minConfidence = 0.35;
        }
    }
}
