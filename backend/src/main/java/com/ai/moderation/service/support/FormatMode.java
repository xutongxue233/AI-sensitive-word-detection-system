package com.ai.moderation.service.support;

/**
 * 结构化输出兼容档位,从严到松。不同 OpenAI 兼容端点支持程度不一,
 * {@link com.ai.moderation.service.AiModerationClient} 首条命中按档位探测,
 * 遇到不支持的 400 自动降级并记忆当前端点的可用档位。
 */
public enum FormatMode {
    /** response_format / text.format = json_schema(OpenAI Structured Outputs,最严格) */
    JSON_SCHEMA,
    /** response_format / text.format = json_object(JSON mode,兼容性更广) */
    JSON_OBJECT,
    /** 不下发结构化约束,纯靠 prompt 约束 + 容错解析 */
    NONE
}
