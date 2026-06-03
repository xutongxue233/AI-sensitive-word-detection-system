package com.ai.moderation.controller;

import com.ai.moderation.dto.AiConnectionTestRequest;
import com.ai.moderation.dto.AiConnectionTestResponse;
import com.ai.moderation.dto.SettingsResponse;
import com.ai.moderation.dto.SettingsUpdateRequest;
import com.ai.moderation.service.AiModerationClient;
import com.ai.moderation.service.SettingsService;
import org.springframework.web.bind.annotation.*;

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
