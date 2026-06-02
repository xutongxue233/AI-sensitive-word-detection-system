package com.ai.moderation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.asr")
public record AsrProperties(boolean enabled, String baseUrl, String transcribePath) {
}

