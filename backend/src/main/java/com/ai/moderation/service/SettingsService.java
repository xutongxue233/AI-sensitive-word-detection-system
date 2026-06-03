package com.ai.moderation.service;

import com.ai.moderation.config.AiProperties;
import com.ai.moderation.config.AiProperties.ApiType;
import com.ai.moderation.config.ClipProperties;
import com.ai.moderation.domain.AppSetting;
import com.ai.moderation.dto.SettingsResponse;
import com.ai.moderation.dto.SettingsUpdateRequest;
import com.ai.moderation.repository.AppSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * 运行时系统设置中心。设置持久化在 app_settings 单行(id=1),支持前台即时调整。
 * 首次访问若无记录,则用 application.yml 的 AiProperties/ClipProperties 默认值种子化。
 * 内存缓存当前设置,更新时刷新,避免 AI 复核逐条命中时频繁查库。
 */
@Service
public class SettingsService {
    private static final Long ROW_ID = 1L;

    private final AppSettingRepository repository;
    private final AiProperties aiDefaults;
    private final ClipProperties clipDefaults;
    private volatile AppSetting cache;

    public SettingsService(AppSettingRepository repository, AiProperties aiDefaults, ClipProperties clipDefaults) {
        this.repository = repository;
        this.aiDefaults = aiDefaults;
        this.clipDefaults = clipDefaults;
    }

    public AiProperties currentAi() {
        AppSetting s = load();
        return new AiProperties(
                Boolean.TRUE.equals(s.getAiEnabled()),
                parseApiType(s.getAiApiType()),
                s.getAiBaseUrl(),
                s.getAiApiKey(),
                s.getAiModel(),
                s.getAiTemperature() == null ? 0 : s.getAiTemperature(),
                s.getAiConfidenceThreshold() == null ? 0.6 : s.getAiConfidenceThreshold(),
                s.getAiTimeoutSeconds() == null ? 60 : s.getAiTimeoutSeconds()
        );
    }

    public ClipProperties currentClip() {
        AppSetting s = load();
        return new ClipProperties(
                s.getClipPaddingSeconds() == null ? 0.2 : s.getClipPaddingSeconds(),
                Boolean.TRUE.equals(s.getClipPreciseExport())
        );
    }

    public SettingsResponse currentResponse() {
        AppSetting s = load();
        AiProperties ai = currentAi();
        ClipProperties clip = currentClip();
        return new SettingsResponse(
                ai.enabled(),
                ai.apiType().name(),
                ai.baseUrl(),
                StringUtils.hasText(s.getAiApiKey()),
                ai.model(),
                ai.temperature(),
                ai.confidenceThreshold(),
                ai.timeoutSeconds(),
                clip.paddingSeconds(),
                clip.preciseExport()
        );
    }

    @Transactional
    public SettingsResponse update(SettingsUpdateRequest request) {
        AppSetting s = load();
        if (request.aiEnabled() != null) {
            s.setAiEnabled(request.aiEnabled());
        }
        if (request.aiApiType() != null) {
            s.setAiApiType(parseApiType(request.aiApiType()).name());
        }
        if (request.aiBaseUrl() != null) {
            s.setAiBaseUrl(request.aiBaseUrl().trim());
        }
        if (request.aiApiKey() != null) {
            s.setAiApiKey(request.aiApiKey().trim());
        }
        if (request.aiModel() != null) {
            s.setAiModel(request.aiModel().trim());
        }
        if (request.aiTemperature() != null) {
            s.setAiTemperature(request.aiTemperature());
        }
        if (request.aiConfidenceThreshold() != null) {
            s.setAiConfidenceThreshold(clamp(request.aiConfidenceThreshold(), 0, 1));
        }
        if (request.aiTimeoutSeconds() != null) {
            s.setAiTimeoutSeconds(Math.max(1, request.aiTimeoutSeconds()));
        }
        if (request.clipPaddingSeconds() != null) {
            s.setClipPaddingSeconds(Math.max(0, request.clipPaddingSeconds()));
        }
        if (request.clipPreciseExport() != null) {
            s.setClipPreciseExport(request.clipPreciseExport());
        }
        s.setUpdatedAt(Instant.now());
        repository.updateById(s);
        cache = s;
        return currentResponse();
    }

    private AppSetting load() {
        AppSetting current = cache;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cache != null) {
                return cache;
            }
            AppSetting row = repository.findById(ROW_ID).orElseGet(this::seedDefaults);
            cache = row;
            return row;
        }
    }

    private AppSetting seedDefaults() {
        AppSetting row = new AppSetting();
        row.setId(ROW_ID);
        row.setAiEnabled(aiDefaults.enabled());
        row.setAiApiType(aiDefaults.apiType().name());
        row.setAiBaseUrl(aiDefaults.baseUrl());
        row.setAiApiKey(aiDefaults.apiKey());
        row.setAiModel(aiDefaults.model());
        row.setAiTemperature(aiDefaults.temperature());
        row.setAiConfidenceThreshold(aiDefaults.confidenceThreshold());
        row.setAiTimeoutSeconds(aiDefaults.timeoutSeconds());
        row.setClipPaddingSeconds(clipDefaults.paddingSeconds());
        row.setClipPreciseExport(clipDefaults.preciseExport());
        row.setUpdatedAt(Instant.now());
        repository.insert(row);
        return row;
    }

    private ApiType parseApiType(String value) {
        if (value == null) {
            return ApiType.CHAT;
        }
        try {
            return ApiType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ApiType.CHAT;
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
