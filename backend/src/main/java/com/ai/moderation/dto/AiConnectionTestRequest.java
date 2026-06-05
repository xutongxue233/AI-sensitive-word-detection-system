package com.ai.moderation.dto;

/**
 * AI 连接测试请求。前端「测试连接」按钮携带一组临时端点参数发起探活,
 * 这些参数仅用于本次连通性验证、不落库,目的是在保存设置前先确认端点/密钥/模型可用。
 *
 * @param aiApiType       接口形态:CHAT(/v1/chat/completions)或 RESPONSES(/v1/responses)
 * @param aiBaseUrl       OpenAI 兼容端点基址
 * @param aiApiKey        访问密钥(临时,不持久化)
 * @param aiModel         待测试的模型名
 * @param aiTemperature   采样温度
 * @param aiTimeoutSeconds 探活超时秒数
 */
public record AiConnectionTestRequest(
        String aiApiType,
        String aiBaseUrl,
        String aiApiKey,
        String aiModel,
        Double aiTemperature,
        Integer aiTimeoutSeconds
) {
}
