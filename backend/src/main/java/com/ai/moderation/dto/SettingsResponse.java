package com.ai.moderation.dto;

/**
 * 系统设置响应。出于安全考虑不回传明文 apiKey,仅以 aiApiKeyConfigured 表示是否已配置。
 *
 * @param aiEnabled            是否启用 AI 复核(关闭时走本地规则兜底)
 * @param aiApiType            接口形态:CHAT(/v1/chat/completions)或 RESPONSES(/v1/responses)
 * @param aiBaseUrl            OpenAI 兼容端点基址
 * @param aiApiKeyConfigured   是否已配置密钥(替代明文 key 回传,避免泄露)
 * @param aiModel              使用的模型名
 * @param aiTemperature        采样温度
 * @param aiConfidenceThreshold 置信度阈值:仅达到该值的违规判定才进时间轴并生成剪辑(默认 0.6)
 * @param aiTimeoutSeconds     单次调用超时秒数
 * @param asrProvider          ASR 引擎:LOCAL 本地 Whisper / ONLINE 在线接口(如 MiMo)
 * @param asrOnlineBaseUrl     在线 ASR 基址(OpenAI Chat Completions 兼容)
 * @param asrOnlineApiKeyConfigured 在线 ASR 密钥是否已配置(不回传明文)
 * @param asrOnlineModel       在线 ASR 模型名
 * @param clipPaddingSeconds   剪辑区间前后留白(秒)
 * @param clipPreciseExport    是否精确导出(影响裁切边界处理策略)
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
        String asrProvider,
        String asrOnlineBaseUrl,
        boolean asrOnlineApiKeyConfigured,
        String asrOnlineModel,
        double clipPaddingSeconds,
        boolean clipPreciseExport
) {
}
