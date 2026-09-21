package com.ai.moderation.dto;

/**
 * 系统设置更新请求。所有字段可选,null 表示保持原值不变。
 * aiApiKey 为 null 时保留原 key;传入非空字符串则更新,传入空字符串则清空。
 *
 * @param aiEnabled            是否启用 AI 复核;null 保持不变
 * @param aiApiType            接口形态(CHAT/RESPONSES);null 保持不变
 * @param aiBaseUrl            OpenAI 兼容端点基址;null 保持不变
 * @param aiApiKey             访问密钥,三态:null 保留原值 / 非空更新 / 空串清空
 * @param aiModel              模型名;null 保持不变
 * @param aiTemperature        采样温度;null 保持不变
 * @param aiConfidenceThreshold 置信度阈值;null 保持不变
 * @param aiTimeoutSeconds     单次调用超时秒数;null 保持不变
 * @param clipPaddingSeconds   剪辑区间前后留白(秒);null 保持不变
 * @param clipPreciseExport    是否精确导出;null 保持不变
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
