package com.ai.moderation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
    public enum ApiType {
        CHAT,
        RESPONSES
    }

    public AiProperties {
        if (apiType == null) {
            apiType = ApiType.CHAT;
        }
        if (confidenceThreshold <= 0) {
            confidenceThreshold = 0.6;
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 60;
        }
    }
}
