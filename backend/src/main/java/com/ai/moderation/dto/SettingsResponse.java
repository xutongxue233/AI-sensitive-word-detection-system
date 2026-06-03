package com.ai.moderation.dto;

/**
 * 系统设置响应。出于安全考虑不回传明文 apiKey,仅以 aiApiKeyConfigured 表示是否已配置。
 */
public record SettingsResponse(
        boolean aiEnabled,
        String aiApiType,
        String aiBaseUrl,
        boolean aiApiKeyConfigured,
        String aiModel,
        double aiTemperature,
        double aiConfidenceThreshold,
        int aiTimeoutSeconds,
        double clipPaddingSeconds,
        boolean clipPreciseExport
) {
}
