package com.ai.moderation.controller;

import com.ai.moderation.dto.AiConnectionTestRequest;
import com.ai.moderation.dto.AiConnectionTestResponse;
import com.ai.moderation.dto.SettingsResponse;
import com.ai.moderation.dto.SettingsUpdateRequest;
import com.ai.moderation.service.AiModerationClient;
import com.ai.moderation.service.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行时设置的 REST 入口,前缀 {@code /api/v1/settings}。
 *
 * <p>聚合 {@link SettingsService}(读取/更新 AI 与剪辑参数,落 {@code app_settings} 单行表)
 * 与 {@link AiModerationClient}(用提交的连接参数实测 AI 端点可达性)。
 *
 * <p>对应前端齿轮面板:这里的更新对**新建任务**立即生效、无需重启;
 * {@code GET} 出于安全不回传明文 API Key,仅由 {@link SettingsResponse} 暴露是否已配置。
 */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {
    private final SettingsService settingsService;
    private final AiModerationClient aiModerationClient;

    public SettingsController(SettingsService settingsService, AiModerationClient aiModerationClient) {
        this.settingsService = settingsService;
        this.aiModerationClient = aiModerationClient;
    }

    @GetMapping
    public SettingsResponse get() {
        return settingsService.currentResponse();
    }

    @PutMapping
    public SettingsResponse update(@RequestBody SettingsUpdateRequest request) {
        return settingsService.update(request);
    }

    @PostMapping("/ai/test")
    public AiConnectionTestResponse testAiConnection(@RequestBody AiConnectionTestRequest request) {
        return aiModerationClient.testConnection(request);
    }
}
