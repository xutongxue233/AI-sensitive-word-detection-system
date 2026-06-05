package com.ai.moderation.config;

/**
 * 大模型审核接口的调用风格。运行时由系统设置 {@code app.ai.api-type} 决定,
 * 影响 {@link com.ai.moderation.service.AiModerationClient} 请求的端点与结构化输出参数形态。
 */
public enum ApiType {
    /** Chat Completions 风格:POST /v1/chat/completions */
    CHAT,
    /** Responses 风格:POST /v1/responses */
    RESPONSES
}
