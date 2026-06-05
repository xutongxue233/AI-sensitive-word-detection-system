package com.ai.moderation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地文件存储参数,绑定 {@code app.storage.*}。
 *
 * @param rootPath 本地存储根目录,存放上传产物与转写临时目录(如 {@code job-{id}/audio.wav},任务结束清理)
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(String rootPath) {
}

