package com.ai.moderation.service.support;

import com.ai.moderation.config.AsrProvider;

/**
 * 在线 ASR 的运行时设置投影(来自 app_settings 单行,经 SettingsService 兜底默认值)。
 * provider 为 ONLINE 时,WhisperAsrClient 把端点/密钥/模型以 form 字段透传给 asr-service,
 * 由其调用 OpenAI Chat Completions 兼容的在线识别接口(如小米 MiMo)。
 *
 * @param provider 引擎选择:LOCAL 本地 Whisper / ONLINE 在线接口
 * @param baseUrl  在线 ASR 基址(默认 https://api.xiaomimimo.com)
 * @param apiKey   在线 ASR 访问密钥
 * @param model    在线 ASR 模型名(默认 mimo-v2.5-asr)
 */
public record AsrOnlineSettings(AsrProvider provider, String baseUrl, String apiKey, String model) {
    public boolean online() {
        return provider == AsrProvider.ONLINE;
    }
}
