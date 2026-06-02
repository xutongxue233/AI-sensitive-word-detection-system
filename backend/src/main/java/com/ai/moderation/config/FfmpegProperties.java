package com.ai.moderation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ffmpeg")
public record FfmpegProperties(String ffmpegPath, String ffprobePath) {
}

