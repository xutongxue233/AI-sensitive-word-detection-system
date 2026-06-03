package com.ai.moderation.dto;

/**
 * 系统设置更新请求。所有字段可选,null 表示保持原值不变。
 * aiApiKey 为 null 时保留原 key;传入非空字符串则更新,传入空字符串则清空。
 */
public record SettingsUpdateRequest(
        Boolean aiEnabled,
        String aiApiType,
        String aiBaseUrl,
        String aiApiKey,
        String aiModel,
        Double aiTemperature,
        Double aiConfidenceThreshold,
        Integer aiTimeoutSeconds,
        Double clipPaddingSeconds,
        Boolean clipPreciseExport
) {
}
