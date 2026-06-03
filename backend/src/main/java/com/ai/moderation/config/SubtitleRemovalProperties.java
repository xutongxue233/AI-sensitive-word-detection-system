package com.ai.moderation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 画面字幕去除引擎配置。
 * - engine=vsr:   调用独立的 vsr-service(Video-Subtitle-Remover, AI inpainting),按违规时间段切片处理后拼回
 * - engine=delogo:用 ffmpeg delogo 邻域插值修复(快,纯 CPU,无需额外服务)
 * VSR 不可用或处理失败时自动回退 delogo。
 */
@ConfigurationProperties(prefix = "app.subtitle-removal")
public record SubtitleRemovalProperties(
        String engine,
        String baseUrl,
        String removePath,
        String inpaintMode,
        int timeoutSeconds
) {
    public boolean useVsr() {
        return engine != null && engine.equalsIgnoreCase("vsr");
    }
}
