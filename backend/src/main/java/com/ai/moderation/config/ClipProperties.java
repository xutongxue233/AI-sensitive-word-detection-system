package com.ai.moderation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.clip")
public record ClipProperties(double paddingSeconds, boolean preciseExport) {
    public ClipProperties {
        if (paddingSeconds < 0) {
            paddingSeconds = 0.2;
        }
    }
}
