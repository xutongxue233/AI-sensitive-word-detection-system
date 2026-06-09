package com.ai.moderation.controller;

import com.ai.moderation.dto.SystemHealthResponse;
import com.ai.moderation.service.SystemHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统健康状态 REST 入口,前缀 {@code /api/v1/system}。
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemHealthController {
    private final SystemHealthService healthService;

    public SystemHealthController(SystemHealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public SystemHealthResponse health() {
        return healthService.check();
    }
}
