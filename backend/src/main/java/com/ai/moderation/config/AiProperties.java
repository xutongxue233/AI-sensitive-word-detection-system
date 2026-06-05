package com.ai.moderation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 上下文复核的连接与判定参数,绑定 {@code app.ai.*}。
 *
 * <p>这里的值仅在 {@code app_settings} 表无记录时用于种子化;系统运行后实际生效的配置
 * 由 {@link com.ai.moderation.service.SettingsService} 从单行设置表读取并缓存,前端齿轮即时修改、
 * 对新建任务立即生效而无需重启。因此除冷启动种子化外,不要直接依赖此 record 的值做业务判定。
 *
 * @param enabled             是否启用 AI 复核;关闭时管线回退为本地逐条规则兜底
 * @param apiType             OpenAI 兼容接口形态 CHAT/RESPONSES,详见 {@link ApiType}
 * @param baseUrl             AI 网关基址(OpenAI 兼容)
 * @param apiKey              API 密钥;{@code GET /settings} 不回传明文,仅返回是否已配置
 * @param model               模型名称
 * @param temperature         采样温度
 * @param confidenceThreshold 违规判定置信度阈值,默认 0.6;仅当 violation 为真且置信度 >= 阈值才判为违规并生成剪辑
 * @param timeoutSeconds      单次调用超时秒数,默认 60
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        boolean enabled,
        ApiType apiType,
        String baseUrl,
        String apiKey,
        String model,
        double temperature,
        double confidenceThreshold,
        int timeoutSeconds
) {
    /** 紧凑构造器:对未配置或非法的关键参数兜底,避免冷启动种子化时落入无效状态。 */
    public AiProperties {
        // 未指定接口形态时默认 Chat Completions(国产网关兼容性最好)
        if (apiType == null) {
            apiType = ApiType.CHAT;
        }
        // 阈值缺省或非法回到 0.6,防止把全部命中误判为违规
        if (confidenceThreshold <= 0) {
            confidenceThreshold = 0.6;
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 60;
        }
    }
}
